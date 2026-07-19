package com.example.llamadroid.sd

enum class SdParamsBackendMode(val storedValue: String, val cliValue: String?) {
    AUTO("auto", null),
    DISK("disk", "disk");

    companion object {
        fun fromStoredValue(value: String?): SdParamsBackendMode =
            entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: AUTO
    }
}

enum class SdRuntimeBackendMode(val storedValue: String, val cliValue: String?) {
    AUTO("auto", null),
    CPU("cpu", "cpu");

    companion object {
        fun fromStoredValue(value: String?): SdRuntimeBackendMode =
            entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: AUTO
    }
}
