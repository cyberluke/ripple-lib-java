package com.ripple.client;

import com.ripple.client.subscriptions.ServerInfo;
import com.ripple.core.coretypes.Amount;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ServerInfoTest {
    @Test(expected = IllegalStateException.class)
    public void test_LedgerInfo_doesnt_rely_on_defaults() {
        new ServerInfo().transactionFee(null);
    }

    @Test
    public void test_LedgerInfo_can_compute_a_transactionFee() {
        ServerInfo info = new ServerInfo();

        // These fields are needed (package accessible, we are all adults here ;)
        info.updated     =   true;
        info.fee_base    =     10;
        info.fee_ref     =     10;
        info.load_base   =    256;
        info.load_factor =    256;

        Amount fee = info.transactionFee(null);
        assertEquals(fee, amt("10"));
    }

    private Amount amt(String s) {
        return Amount.fromString(s);
    }
}
