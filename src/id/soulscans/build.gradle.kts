import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Soul Scans"
    versionCode = 35
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "id"
        baseUrl {
            custom("https://v1.soulscans.org")
        }
        id = 8061354444776372735L
    }

    deeplink {
        path("/comic/..*")
    }
}
