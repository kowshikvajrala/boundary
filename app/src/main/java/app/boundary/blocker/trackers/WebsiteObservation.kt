package app.boundary.blocker.trackers

data class WebsiteObservation(
    val packageName: String,
    val domain: String,
    val urlIdentifier: String
)
