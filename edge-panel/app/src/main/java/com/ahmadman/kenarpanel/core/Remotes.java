package com.ahmadman.kenarpanel.core;

/** The LG TV keys of the panel: power and the two HDMI inputs. */
public final class Remotes {

    private Remotes() {
    }

    /** LG TVs listen on NEC address 0x04. */
    public static final int LG_ADDRESS = 0x04;

    public enum Key {
        /** Same code as the power key of the LG remote: turns the TV on or off. */
        POWER(0x08),
        /** LG's direct input codes (not on the normal remote). */
        HDMI1(0xCE),
        HDMI2(0xCC);

        public final int command;

        Key(int command) {
            this.command = command;
        }

        public Ir.Frame frame() {
            return Ir.nec(LG_ADDRESS, command);
        }
    }
}
