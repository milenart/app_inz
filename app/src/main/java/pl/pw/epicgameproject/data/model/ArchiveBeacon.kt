package pl.pw.epicgameproject.data.model

data class ArchiveBeacon(
    val longitude: Double,
    val latitude: Double,
    val beaconUid: String?,
)

data class BeaconFile(
    val items: List<ArchiveBeacon>?
)


//data class BeaconItem(
//    val latitude: Double?,
//    val longitude: Double?,
//    val beaconUid: String?
//)
