package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.Processing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wild-card routing decision (iOS `DemoModel.swift:594-640`), a pure function. */
class ExtractionRepositoryTest {
    @Test fun billOfLadingRoutesToCloud() = assertEquals(
        WildCardRoute(DocType.BOL, Processing.Cloud, ModelSize.Large),
        wildCardRoute("bill_of_lading"),
    )

    @Test fun itemLabelRoutesToOnDeviceLarge() = assertEquals(
        WildCardRoute(DocType.IL, Processing.Device, ModelSize.Large),
        wildCardRoute("item_label"),
    )

    @Test fun shippingLabelRoutesToOnDeviceLarge() = assertEquals(
        WildCardRoute(DocType.SL, Processing.Device, ModelSize.Large),
        wildCardRoute("shipping_label"),
    )

    @Test fun unknownClassHasNoRoute() = assertNull(wildCardRoute("invoice"))

    @Test fun missingClassHasNoRoute() = assertNull(wildCardRoute(null))

    @Test fun unsupportedDocumentMessageDistinguishesMissingFromUnrecognized() {
        assertEquals("Document Type extraction failed!", UnsupportedDocumentException(null).message)
        assertEquals("We do not support extraction of this document type yet!", UnsupportedDocumentException("invoice").message)
    }
}
