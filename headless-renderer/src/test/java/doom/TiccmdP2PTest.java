package doom;

import com.doom.common.TikcmdBus;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** The P2P deathmatch tic command format must carry the look fields intact. */
public class TiccmdP2PTest {

    private static ticcmd_t sample(short lookdelta, char lookfly) {
        ticcmd_t c = new ticcmd_t();
        c.forwardmove = 0x32;
        c.sidemove    = -0x28;
        c.angleturn   = (short) -1234;
        c.consistancy = (short) 0x7abc;
        c.chatchar    = 'x';
        c.buttons     = 0x85;
        c.lookdelta   = lookdelta;
        c.lookfly     = lookfly;
        return c;
    }

    @Test
    public void p2pLengthMatchesBusSlotSize() {
        assertEquals(TikcmdBus.TIKCMD_BYTES, ticcmd_t.P2P_TICCMDLEN);
        assertEquals(ticcmd_t.P2P_TICCMDLEN, new ticcmd_t().packP2P().length);
    }

    @Test
    public void roundTripKeepsEveryField() {
        for (short look : new short[] { 0, 1, -1, 200, -200, Short.MAX_VALUE, Short.MIN_VALUE }) {
            ticcmd_t in = sample(look, (char) 0x0E);
            ticcmd_t out = new ticcmd_t();
            out.unpackP2P(in.packP2P());

            assertEquals(in.forwardmove, out.forwardmove);
            assertEquals(in.sidemove, out.sidemove);
            assertEquals(in.angleturn, out.angleturn);
            assertEquals(in.consistancy, out.consistancy);
            assertEquals(in.chatchar, out.chatchar);
            assertEquals(in.buttons, out.buttons);
            assertEquals(in.lookfly, out.lookfly);
            assertEquals("lookdelta " + look, in.lookdelta, out.lookdelta);
        }
    }

    @Test
    public void vanillaPackIsUnchanged() {
        // Demos and the original datagram format stay 8 bytes, look fields excluded.
        ticcmd_t c = sample((short) 77, (char) 3);
        byte[] vanilla = c.pack().clone();
        assertEquals(ticcmd_t.TICCMDLEN, vanilla.length);
        byte[] p2p = c.packP2P();
        byte[] p2pPrefix = new byte[ticcmd_t.TICCMDLEN];
        System.arraycopy(p2p, 0, p2pPrefix, 0, ticcmd_t.TICCMDLEN);
        assertArrayEquals(vanilla, p2pPrefix);
    }

    @Test
    public void copyToIncludesLookdelta() {
        ticcmd_t a = sample((short) -42, (char) 1);
        ticcmd_t b = new ticcmd_t();
        a.copyTo(b);
        assertEquals(-42, b.lookdelta);
        assertEquals(1, b.lookfly);
    }
}
