package org.openridesync.app.vehicles

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.honda.wire.HondaWire.*

class VehicleMetadataTest {
    @Test fun requestScopeAndFullSeriesLabelRemainDistinctFromVehicleCode() {
        assertTrue(catalogRequest(true).onlyOwnCountry)
        assertFalse(catalogRequest(false).onlyOwnCountry)
        assertEquals(listOf(1, 2, 3), catalogRequest(false).meterGenerationsList)
        assertEquals(listOf("synthetic-series"), seriesRequest("synthetic-series").modelSeriesLabelsList)
        assertFalse(seriesRequest("synthetic-series").onlyOwnCountry)
    }

    @Test fun orderingUsesFirstMetadataYearAndImageUsesFirstDefaultThenFirst() {
        val oldFirst = Model.newBuilder().setModelLabel("older-first").addModelYears(2020).addModelYears(2027)
            .addImages(Image.newBuilder().setId("front"))
            .addImages(Image.newBuilder().setId("default").setDescription(ModelImageDescription.newBuilder().setIsDefault(true))).build()
        val newerFirst = Model.newBuilder().setModelLabel("newer-first").addModelYears(2025).build()
        val response = ListModelSeriesResponse.newBuilder().addModelSeriesList(ModelSeries.newBuilder()
            .addModels(oldFirst).addModels(newerFirst)).build()
        assertEquals(listOf("newer-first", "older-first"), orderedModels(response).map { it.modelLabel })
        assertEquals(listOf(2020, 2027), orderedModels(response)[1].modelYearsList)
        assertEquals("default", imageId(oldFirst))
        assertEquals("front", imageId(oldFirst.toBuilder().removeImages(1).build()))
        assertNull(imageId(newerFirst))
    }

    @Test fun previousYearMustStillExistInUpdatedMetadata() {
        val updated = Model.newBuilder().addModelYears(2025).addModelYears(2024).build()
        assertEquals(2024, selectedYear(updated, 2024))
        assertEquals(2025, selectedYear(updated, 2020))
        assertEquals(2025, selectedYear(updated, null))
        assertEquals(0, selectedYear(Model.getDefaultInstance(), 2025))
    }
}
