package gavinx.temperatureapi.client;

import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side cache of daily diurnal parameters per dimension and day. */
public final class DiurnalClientState {
    // Key: dimension id string
    private static final Map<String, Entry> byDim = new ConcurrentHashMap<>();

    private DiurnalClientState() {}

    public static void put(String dimId, long dayIndex, double M, double m) {
        byDim.put(dimId, new Entry(dayIndex, M, m));
    }

    public static Entry get(String dimId) {
        return byDim.get(dimId);
    }

    public static final class Entry {
        public final long dayIndex;
        public final double M;
        public final double m;
        public Entry(long dayIndex, double M, double m) {
            this.dayIndex = dayIndex;
            this.M = M;
            this.m = m;
        }
    }
}
