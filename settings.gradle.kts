pluginManagement {
    repositories {
        // 官方仓库优先：阿里云镜像对刚发布的 AGP 主版本常有滞后
        google()
        mavenCentral()
        gradlePluginPortal()
        // 兜底镜像（官方不可达时才会用到）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "CourseSchedule"
include(":app")
