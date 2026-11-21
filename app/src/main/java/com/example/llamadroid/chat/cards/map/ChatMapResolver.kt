package com.example.llamadroid.chat.cards.map

import com.example.llamadroid.chat.cards.ChatCard

/** A map-card place after geocoding; [point] is null when neither Photon nor the model coordinates gave a location. */
data class ResolvedPlace(val index: Int, val name: String, val note: String?, val point: GeoPoint?)

object ChatMapResolver {
    /** Photon by name first (the model's own coordinates are often wrong), model lat/lng as the fallback. */
    fun resolve(card: ChatCard.MapCard, geocoder: PhotonGeocoder): List<ResolvedPlace> =
        card.places.take(MAX_PLACES).mapIndexed { i, place ->
            val modelPoint = if (place.lat != null && place.lng != null && PhotonGeocoder.validCoords(place.lat, place.lng)) {
                GeoPoint(place.lat, place.lng)
            } else null
            ResolvedPlace(i, place.name, place.note, geocoder.geocode(place.name) ?: modelPoint)
        }

    /** Coordinates the model supplied, usable immediately while geocoding runs. */
    fun modelOnly(card: ChatCard.MapCard): List<ResolvedPlace> = card.places.take(MAX_PLACES).mapIndexed { i, place ->
        val p = if (place.lat != null && place.lng != null && PhotonGeocoder.validCoords(place.lat, place.lng)) GeoPoint(place.lat, place.lng) else null
        ResolvedPlace(i, place.name, place.note, p)
    }

    const val MAX_PLACES = 12
}
