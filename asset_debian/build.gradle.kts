plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("asset_debian")
    dynamicDelivery {
        // Debian is part of the installed app experience. It must not be fetched as an
        // executable package after install; apt remains an explicit guest-user action.
        deliveryType.set("install-time")
    }
}
