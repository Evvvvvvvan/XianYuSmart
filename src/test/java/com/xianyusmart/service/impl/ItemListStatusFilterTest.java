package com.xianyusmart.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemListStatusFilterTest {

    @Test
    void explicitStatusWinsOverOnlyOnSale() {
        assertEquals(Integer.valueOf(2), ItemServiceImpl.resolveStatusFilter(2, true));
        assertEquals(Integer.valueOf(-1), ItemServiceImpl.resolveStatusFilter(-1, false));
        assertEquals(Integer.valueOf(-9), ItemServiceImpl.resolveStatusFilter(-9, null));
    }

    @Test
    void onlyOnSaleFallsBackToStatusZero() {
        assertEquals(Integer.valueOf(0), ItemServiceImpl.resolveStatusFilter(null, true));
    }

    @Test
    void allStatusesWhenNeitherProvided() {
        assertNull(ItemServiceImpl.resolveStatusFilter(null, false));
        assertNull(ItemServiceImpl.resolveStatusFilter(null, null));
    }
}
