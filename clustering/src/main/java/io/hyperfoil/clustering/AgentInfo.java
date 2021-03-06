package io.hyperfoil.clustering;

import java.util.HashMap;
import java.util.Map;

import io.hyperfoil.api.session.PhaseInstance;

public class AgentInfo {
   final String name;
   final String address;
   Status status = Status.REGISTERED;
   Map<String, PhaseInstance.Status> phases = new HashMap<>();

   public AgentInfo(String name, String address) {
      this.name = name;
      this.address = address;
   }

   public enum Status {
      REGISTERED,
      INITIALIZING,
      INITIALIZED,
      FAILED
   }
}
