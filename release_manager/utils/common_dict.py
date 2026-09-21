def _flatten_docker_images(options_by_distribution):
    flattened_images = {}
    for version_options in options_by_distribution.values():
        for images in version_options.values():
            for image in images:
                flattened_images[image] = image
    return flattened_images


def _build_default_docker_images(options_by_distribution):
    defaults = {}
    for distribution, version_options in options_by_distribution.items():
        defaults[distribution] = {}
        for java_version, images in version_options.items():
            defaults[distribution][java_version] = images[0]
    return defaults


class CommonDict(dict):
    """
    全局字典类，用于存储常用配置项。
    """

    _instance = None

    PKG_ENV = "pkg_env"
    PKG_ENV_DICT = {
        "dev": "开发环境",
        "test": "测试环境",
        "prod": "大中华生产",
        "prod_na": "北美生产",
        "prod_eu": "欧洲生产",
    }

    DEFAULT_CLOUD_VENDOR = "general"
    CLOUD_VENDORS = {
        "general": "通用",
        "aliyun": "阿里云",
        "huawei": "华为云",
        "tencent": "腾讯云",
    }

    DEFAULT_JAVA_DISTRIBUTION = "temurin"
    JAVA_DISTRIBUTIONS = {
        "temurin": "Eclipse Temurin (Adoptium)",
        "dragonwell": "Alibaba Dragonwell",
        "bisheng": "Huawei BiSheng JDK",
        "kona": "Tencent Kona JDK",
        "corretto": "Amazon Corretto",
        "microsoft": "Microsoft Build of OpenJDK",
        "zulu": "Azul Zulu",
        "liberica": "BellSoft Liberica",
        "openjdk": "OpenJDK",
    }
    DEFAULT_JAVA_DISTRIBUTION_BY_CLOUD_VENDOR = {
        "general": "temurin",
        "aliyun": "dragonwell",
        "huawei": "bisheng",
        "tencent": "kona",
    }

    JAVA_LTS_VERSIONS = {
        "11": "Java 11 (LTS)",
        "17": "Java 17 (LTS)",
        "21": "Java 21 (LTS)",
        "25": "Java 25 (LTS)",
    }

    DOCKER_BASE_IMAGE_OPTIONS_BY_JAVA = {
        "temurin": {
            "11": [
                "eclipse-temurin:11-jre-alpine",
                "eclipse-temurin:11-jdk-alpine",
                "eclipse-temurin:11-jre",
            ],
            "17": [
                "eclipse-temurin:17-jre-alpine",
                "eclipse-temurin:17-jdk-alpine",
                "eclipse-temurin:17-jre",
            ],
            "21": [
                "eclipse-temurin:21-jre-alpine",
                "eclipse-temurin:21-jdk-alpine",
                "eclipse-temurin:21-jre",
            ],
            "25": [
                "eclipse-temurin:25-jre-alpine",
                "eclipse-temurin:25-jdk-alpine",
                "eclipse-temurin:25-jre",
            ],
        },
        "dragonwell": {
            "11": [
                "alibabadragonwell/dragonwell:11-anolis",
                "alibabadragonwell/dragonwell:11-alpine",
                "alibabadragonwell/dragonwell:11",
            ],
            "17": [
                "alibabadragonwell/dragonwell:17-anolis",
                "alibabadragonwell/dragonwell:17-alpine",
                "alibabadragonwell/dragonwell:17",
            ],
            "21": [
                "alibabadragonwell/dragonwell:21-anolis",
                "alibabadragonwell/dragonwell:21-alpine",
                "alibabadragonwell/dragonwell:21",
            ],
            "25": [
                "alibabadragonwell/dragonwell:25-anolis",
                "alibabadragonwell/dragonwell:25-alpine",
                "alibabadragonwell/dragonwell:25",
            ],
        },
        "bisheng": {
            "11": [
                "eclipse-temurin:11-jre-alpine",
                "eclipse-temurin:11-jdk-alpine",
                "eclipse-temurin:11-jre",
            ],
            "17": [
                "openeuler/bisheng-jdk:17.0.10-oe2203sp3",
                "eclipse-temurin:17-jre-alpine",
                "eclipse-temurin:17-jdk-alpine",
            ],
            "21": [
                "openeuler/bisheng-jdk:21.0.5-oe2203sp3",
                "eclipse-temurin:21-jre-alpine",
                "eclipse-temurin:21-jdk-alpine",
            ],
            "25": [
                "eclipse-temurin:25-jre-alpine",
                "eclipse-temurin:25-jdk-alpine",
                "eclipse-temurin:25-jre",
            ],
        },
        "kona": {
            "11": [
                "eclipse-temurin:11-jre-alpine",
                "eclipse-temurin:11-jdk-alpine",
                "eclipse-temurin:11-jre",
            ],
            "17": [
                "eclipse-temurin:17-jre-alpine",
                "eclipse-temurin:17-jdk-alpine",
                "eclipse-temurin:17-jre",
            ],
            "21": [
                "eclipse-temurin:21-jre-alpine",
                "eclipse-temurin:21-jdk-alpine",
                "eclipse-temurin:21-jre",
            ],
            "25": [
                "eclipse-temurin:25-jre-alpine",
                "eclipse-temurin:25-jdk-alpine",
                "eclipse-temurin:25-jre",
            ],
        },
        "corretto": {
            "11": [
                "amazoncorretto:11-alpine-jdk",
                "amazoncorretto:11",
                "amazoncorretto:11-alpine",
            ],
            "17": [
                "amazoncorretto:17-alpine-jdk",
                "amazoncorretto:17",
                "amazoncorretto:17-alpine",
            ],
            "21": [
                "amazoncorretto:21-alpine-jdk",
                "amazoncorretto:21",
                "amazoncorretto:21-alpine",
            ],
            "25": [
                "amazoncorretto:25-alpine-jdk",
                "amazoncorretto:25",
                "amazoncorretto:25-alpine",
            ],
        },
        "microsoft": {
            "11": [
                "mcr.microsoft.com/openjdk/jdk:11-ubuntu",
                "mcr.microsoft.com/openjdk/jdk:11-mariner",
                "mcr.microsoft.com/openjdk/jdk:11",
            ],
            "17": [
                "mcr.microsoft.com/openjdk/jdk:17-ubuntu",
                "mcr.microsoft.com/openjdk/jdk:17-mariner",
                "mcr.microsoft.com/openjdk/jdk:17",
            ],
            "21": [
                "mcr.microsoft.com/openjdk/jdk:21-ubuntu",
                "mcr.microsoft.com/openjdk/jdk:21-mariner",
                "mcr.microsoft.com/openjdk/jdk:21",
            ],
            "25": [
                "mcr.microsoft.com/openjdk/jdk:25-ubuntu",
                "mcr.microsoft.com/openjdk/jdk:25-mariner",
                "mcr.microsoft.com/openjdk/jdk:25",
            ],
        },
        "zulu": {
            "11": [
                "azul/zulu-openjdk-alpine:11-jre",
                "azul/zulu-openjdk-alpine:11",
                "azul/zulu-openjdk:11",
            ],
            "17": [
                "azul/zulu-openjdk-alpine:17-jre",
                "azul/zulu-openjdk-alpine:17",
                "azul/zulu-openjdk:17",
            ],
            "21": [
                "azul/zulu-openjdk-alpine:21-jre",
                "azul/zulu-openjdk-alpine:21",
                "azul/zulu-openjdk:21",
            ],
            "25": [
                "azul/zulu-openjdk-alpine:25-jre",
                "azul/zulu-openjdk-alpine:25",
                "azul/zulu-openjdk:25",
            ],
        },
        "liberica": {
            "11": [
                "bellsoft/liberica-openjre-alpine:11",
                "bellsoft/liberica-openjdk-alpine:11",
                "bellsoft/liberica-openjre-debian:11",
            ],
            "17": [
                "bellsoft/liberica-openjre-alpine:17",
                "bellsoft/liberica-openjdk-alpine:17",
                "bellsoft/liberica-openjre-debian:17",
            ],
            "21": [
                "bellsoft/liberica-openjre-alpine:21",
                "bellsoft/liberica-openjdk-alpine:21",
                "bellsoft/liberica-openjre-debian:21",
            ],
            "25": [
                "bellsoft/liberica-openjre-alpine:25",
                "bellsoft/liberica-openjdk-alpine:25",
                "bellsoft/liberica-openjre-debian:25",
            ],
        },
        "openjdk": {
            "11": [
                "openjdk:11-jdk-slim",
                "openjdk:11",
                "openjdk:11-oraclelinux8",
            ],
            "17": [
                "openjdk:17-jdk-slim",
                "openjdk:17",
                "openjdk:17-oraclelinux8",
            ],
            "21": [
                "openjdk:21-jdk-slim",
                "openjdk:21",
                "openjdk:21-oraclelinux8",
            ],
            "25": [
                "openjdk:25-jdk-slim",
                "openjdk:25",
                "openjdk:25-oraclelinux8",
            ],
        },
    }
    DOCKER_BASE_IMAGE_VERSIONS = _flatten_docker_images(
        DOCKER_BASE_IMAGE_OPTIONS_BY_JAVA
    )
    DEFAULT_DOCKER_BASE_IMAGE_BY_JAVA_DISTRIBUTION = _build_default_docker_images(
        DOCKER_BASE_IMAGE_OPTIONS_BY_JAVA
    )

    DOCKER_REGISTRY = "docker_registry"
    DOCKER_NAMESPACE = "docker_namespace"
    DOCKER_USERNAME = "docker_username"
    DOCKER_PASSWORD = "docker_password"
    PREFERRED_CLOUD_VENDOR = "preferred_cloud_vendor"
    PREFERRED_JAVA_DISTRIBUTION = "preferred_java_distribution"
    PREFERRED_JAVA_VERSION = "preferred_java_version"
    OTEL_ENDPOINT_CN = "otel_endpoint_cn"
    OTEL_METRICS_ENDPOINT_CN = "otel_metrics_endpoint_cn"
    OTEL_ENDPOINT_NA = "otel_endpoint_na"
    OTEL_METRICS_ENDPOINT_NA = "otel_metrics_endpoint_na"
    OTEL_ENDPOINT_EU = "otel_endpoint_eu"
    OTEL_METRICS_ENDPOINT_EU = "otel_metrics_endpoint_eu"
    PROJECT_ROOT_DIR = "project_root_dir"
    OTEL_MONITORING = "otel_monitoring"
    ARTHAS = "arthas"
    DOCKER_BASE_IMAGE_VERSION = "docker_base_image_version"
    SUB_PROJECT_OPERATION_COUNT = "sub_project_operation_count"

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super(CommonDict, cls).__new__(cls, *args, **kwargs)
        return cls._instance

    def __init__(self):
        if not hasattr(self, "initialized"):
            super().__init__()
            self.initialized = True
