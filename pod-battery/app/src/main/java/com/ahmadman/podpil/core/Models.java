package com.ahmadman.podpil.core;

/**
 * Apple / Beats headphone models seen in the proximity-pairing broadcast, with the listening
 * times Apple publishes (used for the "how long is left" estimate).
 */
public final class Models {

    private Models() {
    }

    public static final class Model {
        public final int id;
        public final String name;
        /** One battery (AirPods Max, some Beats) instead of left + right + case. */
        public final boolean single;
        /** Listening time on one charge, minutes. */
        public final int podMinutes;
        /** Listening time including the case's recharges, minutes (= podMinutes when there is no case). */
        public final int totalMinutes;
        public final boolean known;

        Model(int id, String name, boolean single, int podMinutes, int totalMinutes, boolean known) {
            this.id = id;
            this.name = name;
            this.single = single;
            this.podMinutes = podMinutes;
            this.totalMinutes = totalMinutes;
            this.known = known;
        }

        public boolean hasCase() {
            return !single && totalMinutes > podMinutes;
        }
    }

    private static final Model[] ALL = {
            new Model(0x0220, "AirPods (1. nesil)", false, 300, 1440, true),
            new Model(0x0F20, "AirPods (2. nesil)", false, 300, 1440, true),
            new Model(0x1320, "AirPods (3. nesil)", false, 360, 1800, true),
            new Model(0x1920, "AirPods 4", false, 300, 1800, true),
            new Model(0x1B20, "AirPods 4 (ANC)", false, 300, 1800, true),
            new Model(0x0E20, "AirPods Pro", false, 270, 1440, true),
            new Model(0x1420, "AirPods Pro 2", false, 360, 1800, true),
            new Model(0x2420, "AirPods Pro 2 (USB-C)", false, 360, 1800, true),
            new Model(0x2720, "AirPods Pro 3", false, 480, 1440, true),
            new Model(0x0A20, "AirPods Max", true, 1200, 1200, true),
            new Model(0x1F20, "AirPods Max (USB-C)", true, 1200, 1200, true),
            new Model(0x0B20, "Powerbeats Pro", false, 540, 1440, true),
            new Model(0x0320, "Powerbeats3", true, 720, 720, true),
            new Model(0x0520, "BeatsX", true, 480, 480, true),
            new Model(0x1020, "Beats Flex", true, 720, 720, true),
            new Model(0x0620, "Beats Solo3", true, 2400, 2400, true),
            new Model(0x0920, "Beats Studio3", true, 1320, 1320, true),
    };

    public static Model of(int id) {
        for (Model m : ALL) {
            if (m.id == id) {
                return m;
            }
        }
        return new Model(id, "AirPods", false, 300, 1440, false);
    }
}
