package io.hyperfoil.clustering.util;

import io.hyperfoil.api.statistics.StatisticsSnapshot;

public class ReportMessage extends StatsMessage {
   public final int stepId;
   public final String statisticsName;
   public final StatisticsSnapshot statistics;

   public ReportMessage(String address, String runId, int stepId, String statisticsName, StatisticsSnapshot statistics) {
      super(address, runId);
      this.stepId = stepId;
      this.statisticsName = statisticsName;
      this.statistics = statistics;
   }

   public static class Codec extends ObjectCodec<ReportMessage> {}
}
