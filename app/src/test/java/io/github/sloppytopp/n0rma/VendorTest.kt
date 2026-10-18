package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.WifiClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class VendorTest {
    @Before fun load() {
        WifiClassifier.loadKnown(sequenceOf("D4E22F,Roku,known", "DC08DA,Askey,known"))
        WifiClassifier.loadCsv(sequenceOf("040312,Hikvision,camera"))
    }

    @Test fun plainAddressNamesTheMaker() = assertEquals("Roku", WifiClassifier.vendorOf("D4:E2:2F:B6:20:10")?.name)
    @Test fun privateCopyOfAnAddressIsStillRecognised() {
        val v = WifiClassifier.vendorOf("d6:e2:2f:b6:20:10")!!
        assertEquals("Roku", v.name); assertEquals(true, v.privateCopy)
    }
    @Test fun plainAddressIsNotMarkedPrivate() = assertEquals(false, WifiClassifier.vendorOf("dc:08:da:98:e2:5d")!!.privateCopy)
    @Test fun unknownMakerIsNull() { assertNull(WifiClassifier.vendorOf("aa:bb:cc:00:00:01")); assertNull(WifiClassifier.vendorOf("ba:bb:cc:00:00:01")) }
    @Test fun cameraMakerStillWinsAndKeepsItsClass() = assertEquals("Hikvision", WifiClassifier.vendorOf("04:03:12:00:00:01")?.name)
}
