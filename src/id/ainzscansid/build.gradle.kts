import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Ainz Scans ID"
    versionCode = 36
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "id"
        baseUrl {
            custom("https://v3.ainzscans01.com")
        }
    }

    deeplink {
        path("/comic/..*")
    }
}
}
