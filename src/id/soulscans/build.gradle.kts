import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Soul Scans"
    versionCode = 35
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "id"
        baseUrl {
            custom("https://v1.soulscans.org")
        }
        id = 8061354444776372735L
    }

    deeplink {
        host("v1.soulscans.asia")
        path("/comic/..*")
    }
}
