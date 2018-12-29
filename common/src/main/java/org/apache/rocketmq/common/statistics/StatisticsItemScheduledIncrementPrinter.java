/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.common.statistics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class StatisticsItemScheduledIncrementPrinter extends StatisticsItemScheduledPrinter {

    private String[] tpsItemNames;

    public static final int TPS_INITIAL_DELAY = 0;
    public static final int TPS_INTREVAL = 1000;
    public static final String SEPERATOR = "|";

    /**
     * last snapshots of all scheduled items
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, StatisticsItem>> lastItemSnapshots
        = new ConcurrentHashMap<String, ConcurrentHashMap<String, StatisticsItem>>();

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, StatisticsItemSampleBrief>> sampleBriefs
        = new ConcurrentHashMap<String, ConcurrentHashMap<String, StatisticsItemSampleBrief>>();

    public StatisticsItemScheduledIncrementPrinter(String name, StatisticsItemPrinter printer,
                                                   ScheduledExecutorService executor, InitialDelay initialDelay,
                                                   long interval, String[] tpsItemNames, Enable enable) {
        super(name, printer, executor, initialDelay, interval, enable);
        this.tpsItemNames = tpsItemNames;
    }

    /**
     * schedule a StatisticsItem to print the Increments periodically
     */
    @Override
    public void schedule(final StatisticsItem item) {
        setItemSampleBrief(item.getStatKind(), item.getStatObject(), new StatisticsItemSampleBrief(item, tpsItemNames));

        // print log every ${interval} miliseconds
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!enabled()) {
                    return;
                }

                StatisticsItem snapshot = item.snapshot();
                StatisticsItem lastSnapshot = getItemSnapshot(lastItemSnapshots, item.getStatKind(),
                    item.getStatObject());
                StatisticsItem increment = snapshot.subtract(lastSnapshot);

                Interceptor inteceptor = item.getInterceptor();
                String inteceptorStr = formatInterceptor(inteceptor);
                if (inteceptor != null) {
                    inteceptor.reset();
                }

                StatisticsItemSampleBrief brief = getSampleBrief(item.getStatKind(), item.getStatObject());
                if (hasIncreased(increment)) {
                    printer.print(name, increment, inteceptorStr, brief.toString());
                }
                setItemSnapshot(lastItemSnapshots, snapshot);
                brief.reset();
            }
        }, getInitialDelay(), interval, TimeUnit.MILLISECONDS);

        // sample every TPS_INTREVAL
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!enabled()) {
                    return;
                }

                StatisticsItem snapshot = item.snapshot();
                StatisticsItemSampleBrief brief = getSampleBrief(item.getStatKind(), item.getStatObject());
                brief.sample(snapshot);
            }
        }, TPS_INTREVAL, TPS_INTREVAL, TimeUnit.MILLISECONDS);
    }

    private StatisticsItem getItemSnapshot(
        ConcurrentHashMap<String, ConcurrentHashMap<String, StatisticsItem>> snapshots,
        String kind, String key) {
        ConcurrentHashMap<String, StatisticsItem> itemMap = snapshots.get(kind);
        return (itemMap != null) ? itemMap.get(key) : null;
    }

    private StatisticsItemSampleBrief getSampleBrief(String kind, String key) {
        ConcurrentHashMap<String, StatisticsItemSampleBrief> itemMap = sampleBriefs.get(kind);
        return (itemMap != null) ? itemMap.get(key) : null;
    }

    private void setItemSnapshot(ConcurrentHashMap<String, ConcurrentHashMap<String, StatisticsItem>> snapshots,
                                 StatisticsItem item) {
        String kind = item.getStatKind();
        String key = item.getStatObject();
        ConcurrentHashMap<String, StatisticsItem> itemMap = snapshots.get(kind);
        if (itemMap == null) {
            itemMap = new ConcurrentHashMap<String, StatisticsItem>();
            ConcurrentHashMap<String, StatisticsItem> oldItemMap = snapshots.putIfAbsent(kind, itemMap);
            if (oldItemMap != null) {
                itemMap = oldItemMap;
            }
        }

        itemMap.put(key, item);
    }

    private void setItemSampleBrief(String kind, String key,
                                    StatisticsItemSampleBrief brief) {
        ConcurrentHashMap<String, StatisticsItemSampleBrief> itemMap = sampleBriefs.get(kind);
        if (itemMap == null) {
            itemMap = new ConcurrentHashMap<String, StatisticsItemSampleBrief>();
            ConcurrentHashMap<String, StatisticsItemSampleBrief> oldItemMap = sampleBriefs.putIfAbsent(kind, itemMap);
            if (oldItemMap != null) {
                itemMap = oldItemMap;
            }
        }

        itemMap.put(key, brief);
    }

    private boolean hasIncreased(StatisticsItem increment) {
        if (increment.getInvokeTimes().get() == 0) {
            return false;
        }

        for (AtomicLong acc : increment.getItemAccumulates()) {
            if (acc.get() != 0) {
                return true;
            }
        }
        return false;
    }

    private String formatInterceptor(Interceptor interceptor) {
        if (interceptor == null) {
            return "";
        }

        if (interceptor instanceof StatisticsBriefInterceptor) {
            StringBuilder sb = new StringBuilder();
            StatisticsBriefInterceptor briefInterceptor = (StatisticsBriefInterceptor)interceptor;
            for (StatisticsBrief brief : briefInterceptor.getStatisticsBriefs()) {
                long max = brief.getMax();
                long tp999 = Math.min(brief.tp999(), max);
                //sb.append(SEPERATOR).append(brief.getTotal());
                sb.append(SEPERATOR).append(max);
                //sb.append(SEPERATOR).append(brief.getMin());
                sb.append(SEPERATOR).append(String.format("%.2f", brief.getAvg()));
                sb.append(SEPERATOR).append(tp999);
            }
            return sb.toString();
        }
        return "";
    }

    public static class StatisticsItemSampleBrief {
        private StatisticsItem lastSnapshot;

        public String[] itemNames;
        public ItemSampleBrief[] briefs;

        public StatisticsItemSampleBrief(StatisticsItem statItem, String[] itemNames) {
            this.lastSnapshot = statItem.snapshot();
            this.itemNames = itemNames;
            this.briefs = new ItemSampleBrief[itemNames.length];
            for (int i = 0; i < itemNames.length; i++) {
                this.briefs[i] = new ItemSampleBrief();
            }
        }

        public synchronized void reset() {
            for (ItemSampleBrief brief : briefs) {
                brief.reset();
            }
        }

        public synchronized void sample(StatisticsItem snapshot) {
            if (snapshot == null) {
                return;
            }

            for (int i = 0; i < itemNames.length; i++) {
                String name = itemNames[i];

                long lastValue = lastSnapshot != null ? lastSnapshot.getItemAccumulate(name).get() : 0;
                long increment = snapshot.getItemAccumulate(name).get() - lastValue;
                briefs[i].sample(increment);
            }
            lastSnapshot = snapshot;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < briefs.length; i++) {
                ItemSampleBrief brief = briefs[i];
                sb.append(SEPERATOR).append(brief.getMax());
                //sb.append(SEPERATOR).append(brief.getMin());
                sb.append(SEPERATOR).append(String.format("%.2f", brief.getAvg()));
            }
            return sb.toString();
        }
    }

    /**
     * sample brief of a item for a period of time
     */
    public static class ItemSampleBrief {
        private long max;
        private long min;
        private long total;
        private long cnt;

        public ItemSampleBrief() {
            reset();
        }

        public void sample(long value) {
            max = Math.max(max, value);
            min = Math.min(min, value);
            total += value;
            cnt++;
        }

        public void reset() {
            max = 0;
            min = Long.MAX_VALUE;
            total = 0;
            cnt = 0;
        }

        /**
         * Getters
         *
         * @return
         */
        public long getMax() {
            return max;
        }

        public long getMin() {
            return cnt > 0 ? min : 0;
        }

        public long getTotal() {
            return total;
        }

        public long getCnt() {
            return cnt;
        }

        public double getAvg() {
            return cnt != 0 ? ((double)total) / cnt : 0;
        }
    }
}
