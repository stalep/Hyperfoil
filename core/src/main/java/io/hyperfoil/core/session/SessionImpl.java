package io.hyperfoil.core.session;

import io.hyperfoil.api.session.SharedData;
import io.netty.util.concurrent.EventExecutor;
import io.hyperfoil.api.collection.LimitedPool;
import io.hyperfoil.api.connection.Request;
import io.hyperfoil.api.config.Phase;
import io.hyperfoil.api.config.Scenario;
import io.hyperfoil.api.config.Sequence;
import io.hyperfoil.api.connection.HttpConnectionPool;
import io.hyperfoil.api.http.ValidatorResults;
import io.hyperfoil.api.session.SequenceInstance;
import io.hyperfoil.api.session.Session;
import io.hyperfoil.api.statistics.Statistics;
import io.hyperfoil.api.session.PhaseInstance;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

class SessionImpl implements Session, Callable<Void> {
   private static final Logger log = LoggerFactory.getLogger(SessionImpl.class);
   private static final boolean trace = log.isTraceEnabled();

   // Note: HashMap.get() is allocation-free, so we can use it for direct lookups. Replacing put() is also
   // allocation-free, so vars are OK to write as long as we have them declared.
   private final Map<Object, Var> vars = new HashMap<>();
   private final Map<ResourceKey, Resource> resources = new HashMap<>();
   private final List<Var> allVars = new ArrayList<>();
   private final LimitedPool<SequenceInstance> sequencePool;
   private final LimitedPool<Request> requestPool;
   private final Request[] requests;
   private final SequenceInstance[] runningSequences;
   private PhaseInstance phase;
   private int lastRunningSequence = -1;
   private SequenceInstance currentSequence;

   private Map<String, HttpConnectionPool> httpConnectionPools;
   private EventExecutor executor;
   private SharedData sharedData;

   private final ValidatorResults validatorResults = new ValidatorResults();
   private Statistics[] statistics;
   private final int uniqueId;

   SessionImpl(Scenario scenario, int uniqueId) {
      this.sequencePool = new LimitedPool<>(scenario.maxSequences(), SequenceInstance::new);
      this.requests = new Request[16];
      for (int i = 0; i < requests.length; ++i) {
         this.requests[i] = new Request(this);
      }
      this.requestPool = new LimitedPool<>(this.requests);
      this.runningSequences = new SequenceInstance[scenario.maxSequences()];
      this.uniqueId = uniqueId;

      Sequence[] sequences = scenario.sequences();
      for (int i = 0; i < sequences.length; i++) {
         Sequence sequence = sequences[i];
         sequence.reserve(this);
      }
      for (String var : scenario.objectVars()) {
         declare(var);
      }
      for (String var : scenario.intVars()) {
         declareInt(var);
      }
   }

   @Override
   public int uniqueId() {
      return uniqueId;
   }

   @Override
   public HttpConnectionPool httpConnectionPool(String baseUrl) {
      return httpConnectionPools.get(baseUrl);
   }

   @Override
   public EventExecutor executor() {
      return executor;
   }

   @Override
   public SharedData sharedData() {
      return sharedData;
   }

   @Override
   public Phase phase() {
      return phase != null ? phase.definition() : null;
   }

   void registerVar(Var var) {
      allVars.add(var);
   }

   @Override
   public Session declare(Object key) {
      ObjectVar var = new ObjectVar(this);
      vars.putIfAbsent(key, var);
      return this;
   }

   @Override
   public Object getObject(Object key) {
      return ((ObjectVar) requireSet(key)).get();
   }

   @Override
   public Session setObject(Object key, Object value) {
      if (trace) {
         log.trace("#{} {} <- {}", uniqueId, key, value);
      }
      ObjectVar var = getVar(key);
      var.value = value;
      var.set = true;
      return this;
   }


   @Override
   public Session declareInt(Object key) {
      IntVar var = new IntVar(this);
      vars.put(key, var);
      return this;
   }

   @Override
   public int getInt(Object key) {
      IntVar var = requireSet(key);
      if (!var.isSet()) {
         throw new IllegalStateException("Variable " + key + " was not set yet!");
      }
      return var.get();
   }

   @Override
   public Session setInt(Object key, int value) {
      if (trace) {
         log.trace("#{} {} <- {}", uniqueId, key, value);
      }
      this.<IntVar>getVar(key).set(value);
      return this;
   }

   @Override
   public Session addToInt(Object key, int delta) {
      IntVar var = requireSet(key);
      if (trace) {
         log.trace("#{} {} <- {}", uniqueId, key, var.get() + delta);
      }
      var.set(var.get() + delta);
      return this;
   }

   @Override
   public boolean isSet(Object key) {
      return getVar(key).isSet();
   }

   @Override
   public Object activate(Object key) {
      ObjectVar var = getVar(key);
      var.set = true;
      return var.get();
   }

   @Override
   public Session unset(Object key) {
      getVar(key).unset();
      return this;
   }

   @Override
   public <R extends Resource> void declareResource(ResourceKey<R> key, R resource) {
      resources.put(key, resource);
   }

   @SuppressWarnings("unchecked")
   @Override
   public <R extends Resource> R getResource(ResourceKey<R> key) {
      return (R) resources.get(key);
   }

   @Override
   public <V extends Var> V getVar(Object key) {
      Var var = vars.get(key);
      if (var == null) {
         throw new IllegalStateException("Variable " + key + " was not defined!");
      }
      return (V) var;
   }

   private <V extends Var> V requireSet(Object key) {
      Var var = vars.get(key);
      if (var == null) {
         throw new IllegalStateException("Variable " + key + " was not defined!");
      } else if (!var.isSet()) {
         throw new IllegalStateException("Variable " + key + " was not set yet!");
      }
      return (V) var;
   }

   @Override
   public Void call() {
      try {
         runSession();
      } catch (Throwable t) {
         log.error("#{} Uncaught error", t, uniqueId);
         if (phase != null) {
            phase.fail(t);
         }
      }
      return null;
   }

   public void runSession() {
      if (phase.status() == PhaseInstance.Status.TERMINATED ) {
         if (trace) {
            log.trace("#{} Phase is terminated", uniqueId);
         }
         return;
      }
      if (lastRunningSequence < 0) {
         if (trace) {
            log.trace("#{} No sequences to run, ignoring.", uniqueId);
         }
         return;
      }
      if (trace) {
         log.trace("#{} Run ({} runnning sequences)", uniqueId, lastRunningSequence + 1);
      }
      int lastProgressedSequence = -1;
      while (lastRunningSequence >= 0) {
         boolean progressed = false;
         for (int i = 0; i <= lastRunningSequence; ++i) {
            if (phase.status() == PhaseInstance.Status.TERMINATING) {
               if (trace) {
                  log.trace("#{} Phase {} is terminating", uniqueId, phase.definition().name());
               }
               for (int j = 0; j <= lastRunningSequence; ++j) {
                  sequencePool.release(runningSequences[j]);
                  lastRunningSequence = -1;
               }
               cancelRequests();
               reset();
               if (trace) {
                  log.trace("#{} Session terminated", uniqueId);
               }
               phase.notifyTerminated(this);
               return;
            } else if (lastProgressedSequence == i) {
               break;
            }
            currentSequence(runningSequences[i]);
            if (runningSequences[i].progress(this)) {
               progressed = true;
               lastProgressedSequence = i;
               if (runningSequences[i].isCompleted()) {
                  sequencePool.release(runningSequences[i]);
                  if (i == lastRunningSequence) {
                     runningSequences[i] = null;
                  } else {
                     runningSequences[i] = runningSequences[lastRunningSequence];
                     runningSequences[lastRunningSequence] = null;
                  }
                  --lastRunningSequence;
                  lastProgressedSequence = -1;
               }
            }
            currentSequence(null);
         }
         if (!progressed && lastRunningSequence >= 0) {
            if (trace) {
               log.trace("#{} ({}) no progress, not finished.", uniqueId, phase.definition().name());
            }
            return;
         }
      }
      if (trace) {
         log.trace("#{} Session finished", uniqueId);
      }
      if (!requestPool.isFull()) {
         log.warn("#{} Session completed with requests in-flight!", uniqueId);
         cancelRequests();

      }
      reset();
      phase.notifyFinished(this);
   }

   private void cancelRequests() {
      // We need to close all connections used to ongoing requests, despite these might
      // carry requests from independent phases/sessions
      if (!requestPool.isFull()) {
         for (Request request : requests) {
            if (!request.isCompleted()) {
               if (trace) {
                  log.trace("Canceling request on {}", request.connection());
               }
               request.connection().close();
               // Connection close should complete the request
               if (!request.isCompleted()) {
                  request.setCompleted();
                  requestPool.release(request);
               }
            }
         }
      }
   }

   @Override
   public void currentSequence(SequenceInstance current) {
      if (trace) {
         log.trace("#{} Changing sequence {} -> {}", uniqueId, currentSequence, current);
      }
      assert current == null || currentSequence == null;
      currentSequence = current;
   }

   public SequenceInstance currentSequence() {
      return currentSequence;
   }

   @Override
   public void attach(EventExecutor executor, SharedData sharedData, Map<String, HttpConnectionPool> httpConnectionPools, Statistics[] statistics) {
      assert this.executor == null;
      this.executor = executor;
      this.sharedData = sharedData;
      this.httpConnectionPools = httpConnectionPools;
      this.statistics = statistics;
   }

   @Override
   public void start(PhaseInstance phase) {
      if (trace) {
         log.trace("#{} Session starting in {}", uniqueId, phase.definition().name);
      }
      resetPhase(phase);
      for (Sequence sequence : phase.definition().scenario().initialSequences()) {
         sequence.instantiate(this, 0);
      }
      proceed();
   }

   @Override
   public void proceed() {
      executor.submit(this);
   }

   @Override
   public ValidatorResults validatorResults() {
      return validatorResults;
   }

   @Override
   public Statistics statistics(int sequenceId) {
      return statistics[sequenceId];
   }

   @Override
   public Statistics[] statistics() {
      return statistics;
   }

   @Override
   public void reset() {
      assert sequencePool.isFull();
      assert requestPool.isFull();
      for (int i = 0; i < allVars.size(); ++i) {
         allVars.get(i).unset();
      }
   }

   public void resetPhase(PhaseInstance newPhase) {
      // I dislike having non-final phase but it helps not reallocating the resources...
      if (phase == newPhase) {
         return;
      }
      assert phase == null || newPhase.definition().scenario() == phase.definition().scenario();
      assert phase == null || newPhase.definition().sharedResources.equals(phase.definition().sharedResources);
      assert phase == null || phase.status() == PhaseInstance.Status.TERMINATED;
      phase = newPhase;
   }

   @Override
   public void nextSequence(String name) {
      phase.definition().scenario().sequence(name).instantiate(this, 0);
   }

   @Override
   public void stop() {
      for (int i = 0; i <= lastRunningSequence; ++i) {
         sequencePool.release(runningSequences[i]);
         runningSequences[i] = null;
      }
      lastRunningSequence = -1;
      if (trace) {
         log.trace("#{} Stopped.", uniqueId);
      }
   }

   @Override
   public void fail(Throwable t) {
      stop();
      phase.fail(t);
   }

   @Override
   public boolean isActive() {
      return lastRunningSequence >= 0;
   }

   @Override
   public LimitedPool<Request> requestPool() {
      return requestPool;
   }

   public SequenceInstance acquireSequence() {
      return sequencePool.acquire();
   }

   public void enableSequence(SequenceInstance instance) {
      if (lastRunningSequence >= runningSequences.length - 1) {
         throw new IllegalStateException("Maximum number of scheduled sequences exceeded!");
      }
      lastRunningSequence++;
      assert runningSequences[lastRunningSequence] == null;
      runningSequences[lastRunningSequence] = instance;
   }

   @Override
   public String toString() {
      StringBuilder sb = new StringBuilder("#").append(uniqueId)
            .append(" (").append(phase != null ? phase.definition().name : null).append(") ")
            .append(lastRunningSequence + 1).append(" sequences:");
      for (int i = 0; i <= lastRunningSequence; ++i) {
         sb.append(' ');
         runningSequences[i].appendTo(sb);
      }
      return sb.toString();
   }
}