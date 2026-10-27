package org.openridesync.app.ble

import java.util.Locale
import java.util.UUID

/** Values observed before security negotiation; none imply runtime compatibility. */
internal data class BtuInfo(
    val seriesCode: String?,
    val dfuFirmwareVersion: String,
    val firmwareRevision: String,
    val modelNumber: String?,
    val hardwareRevision: String?,
    val softwareRevision: String?,
    val manufacturerName: String?,
    val vehicleType: Int?,
    val displayCapabilityBits: Int?,
    val switchType: Int?,
    val categoryId: Int?,
    val destinationId: Int?,
)

/** Caller owns connect/discover/close. Does not bond, negotiate keys or save a vehicle. */
internal object BtuInformation {
    val VEHICLE_SERVICE: UUID = UUID.fromString("4d87b1ea-528b-4798-b002-e2e1442a2e86")
    val DEVICE_SERVICE: UUID = standard(0x180a)
    val DFU_SERVICE: UUID = UUID.fromString("0ea1bd92-5c48-41ad-9b8f-70da1bd08f35")
    val DFU_VERSION: UUID = UUID.fromString("32c7720d-440a-47a8-a866-a8b6bea9eeb6")
    private val vehicleFields = listOf(
        "5738696d-45b3-475f-81d8-cc685d56ec18",
        "0ad39e0b-b78d-4252-9917-1aa31a915da7",
        "ddd14e97-636d-4338-a798-94cf7ce13667",
        "3f033888-0211-472e-9405-6525d4e4ddc7",
        "5074e0e8-006f-41ac-aa0f-de6e4649a830",
        "55f7910b-8cda-458b-9b54-8a7da8cd79d4",
    ).map(UUID::fromString)

    suspend fun read(session: GattSession): BtuInfo {
        suspend fun optional(service: UUID, field: UUID): ByteArray? =
            if (session.hasCharacteristic(service, field)) session.read(service, field) else null
        // Runtime l7 reads DFU, then DIS, then Vehicle Information before subscriptions.
        val dfu = decodeFirmwareVersion(session.read(DFU_SERVICE, DFU_VERSION))
        // Serial number is intentionally not collected: it is unnecessary for model resolution.
        suspend fun text(id: Int) = optional(DEVICE_SERVICE, standard(id))?.let(::decodeText)
        val model = text(0x2a24)
        val firmware = requireNotNull(text(0x2a26)) { "Missing firmware revision" }
        val hardware = text(0x2a27)
        val software = text(0x2a28)
        val manufacturer = text(0x2a29)
        require(session.hasService(VEHICLE_SERVICE)) { "Missing vehicle information service" }
        val vehicle = vehicleFields.map { optional(VEHICLE_SERVICE, it) }
        return BtuInfo(vehicle[4]?.let(::decodeSeriesCode), dfu, firmware,
            model, hardware, software, manufacturer,
            vehicle[0]?.let(::decodeFirstByte), vehicle[1]?.let(::decodeFirstByte),
            vehicle[2]?.let(::decodeFirstByte), vehicle[3]?.let(::decodeFirstByte),
            vehicle[5]?.let(::decodeFirstByte))
    }

    internal fun decodeFirstByte(bytes: ByteArray): Int {
        require(bytes.isNotEmpty()) { "Empty BTU numeric field" }
        return bytes[0].toInt() and 0xff
    }

    internal fun decodeSeriesCode(bytes: ByteArray): String {
        require(bytes.size in listOf(1, 2, 4)) { "Unexpected series code size" }
        return bytes.reversedArray().joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    internal fun decodeText(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    internal fun decodeFirmwareVersion(bytes: ByteArray): String {
        require(bytes.size == 2) { "Unexpected firmware version size" }
        // OEM formats signed Java Byte values, rather than unsigned firmware octets.
        return "%02d.%02d".format(Locale.ROOT, bytes[0], bytes[1])
    }

    private fun standard(id: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(Locale.ROOT, id))
}
