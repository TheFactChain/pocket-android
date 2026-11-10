package net.typeblog.shelter.vpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeHealthTest {
    @Test public void cachedHistoryCannotConfirmANewProbe() {
        ProbeHealth health=new ProbeHealth();
        assertFalse(health.begin());
        assertFalse(health.observe(1000,10));
        assertTrue(health.begin());
        assertFalse(health.observe(1000,20));
        assertTrue(health.pending());
        assertTrue(health.observe(1001,30));
        assertFalse(health.pending());
        assertTrue(health.fresh(40));
    }

    @Test public void movingWallClockBackDoesNotExtendHealthOrHideFailure() {
        ProbeHealth health=new ProbeHealth();
        health.observe(2000,100);health.begin();
        assertTrue(health.observe(2001,200));
        assertTrue(health.fresh(75200));
        assertFalse(health.fresh(75201));
        assertTrue(health.begin());
        assertFalse(health.observe(0,80000));
        assertTrue(health.pending());
        health.expire();
        assertFalse(health.fresh(80000));
        assertTrue(health.begin());
        assertTrue(health.observe(50,90000));
        assertTrue(health.fresh(90001));
        assertFalse(health.fresh(89999));
    }
}
