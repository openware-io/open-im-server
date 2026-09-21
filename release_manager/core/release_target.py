from dataclasses import dataclass
import os

from lxml import etree


@dataclass(frozen=True)
class ReleaseTarget:
    domain_module: str
    deploy_module: str
    artifact_id: str
    version: str
    publish_maven: bool
    publish_docker: bool

    @property
    def display_name(self):
        return f"/{self.domain_module}"


class ReleaseTargetResolver:
    NAMESPACE = {"mvn": "http://maven.apache.org/POM/4.0.0"}

    @staticmethod
    def _normalize_module(module):
        return module.lstrip("/\\").replace("\\", "/")

    @staticmethod
    def _read_pom(pom_file):
        tree = etree.parse(pom_file)
        return tree.getroot()

    @staticmethod
    def _text(element, path):
        result = element.find(path, namespaces=ReleaseTargetResolver.NAMESPACE)
        return result.text.strip() if result is not None and result.text else None

    @staticmethod
    def _module_names(root):
        modules = root.find("mvn:modules", namespaces=ReleaseTargetResolver.NAMESPACE)
        if modules is None:
            return []
        return [
            module.text.strip()
            for module in modules.findall("mvn:module", namespaces=ReleaseTargetResolver.NAMESPACE)
            if module.text
        ]

    @staticmethod
    def _is_boot_service(pom_file):
        root = ReleaseTargetResolver._read_pom(pom_file)
        plugins = root.findall(
            "mvn:build/mvn:plugins/mvn:plugin", namespaces=ReleaseTargetResolver.NAMESPACE
        )
        return any(
            ReleaseTargetResolver._text(plugin, "mvn:artifactId")
            == "spring-boot-maven-plugin"
            for plugin in plugins
        )

    @staticmethod
    def _resolve_version(pom_file):
        from core.maven_handler import MavenHandler

        return MavenHandler._resolve_effective_version_text(pom_file)

    @staticmethod
    def _resolve_domain_target(project_root, domain_module):
        domain_module = ReleaseTargetResolver._normalize_module(domain_module)
        domain_pom = os.path.join(project_root, domain_module, "pom.xml")
        if not os.path.isfile(domain_pom):
            return None

        root = ReleaseTargetResolver._read_pom(domain_pom)
        modules = ReleaseTargetResolver._module_names(root)
        api_modules = [module for module in modules if module.endswith("-api")]
        service_modules = [module for module in modules if module.endswith("-service")]
        if len(api_modules) != 1 or len(service_modules) != 1 or len(modules) != 2:
            return None

        deploy_module = f"{domain_module}/{service_modules[0]}"
        deploy_pom = os.path.join(project_root, deploy_module, "pom.xml")
        api_pom = os.path.join(project_root, domain_module, api_modules[0], "pom.xml")
        if not all(os.path.isfile(path) for path in (deploy_pom, api_pom)):
            raise ValueError(f"服务域模块不完整: {domain_module}")
        if not ReleaseTargetResolver._is_boot_service(deploy_pom):
            raise ValueError(f"部署模块不是 Spring Boot 服务: {deploy_module}")

        domain_version = ReleaseTargetResolver._resolve_version(domain_pom)
        child_versions = {
            ReleaseTargetResolver._resolve_version(api_pom),
            ReleaseTargetResolver._resolve_version(deploy_pom),
        }
        if not domain_version or child_versions != {domain_version}:
            raise ValueError(
                f"服务域版本不一致: {domain_module}，父模块={domain_version}，子模块={sorted(child_versions)}"
            )

        deploy_root = ReleaseTargetResolver._read_pom(deploy_pom)
        artifact_id = ReleaseTargetResolver._text(deploy_root, "mvn:artifactId")
        if not artifact_id:
            raise ValueError(f"部署模块缺少 artifactId: {deploy_module}")
        return ReleaseTarget(
            domain_module=domain_module,
            deploy_module=deploy_module,
            artifact_id=artifact_id,
            version=domain_version,
            publish_maven=True,
            publish_docker=True,
        )

    @staticmethod
    def resolve(project_root, selected_module):
        selected_module = ReleaseTargetResolver._normalize_module(selected_module)
        parts = selected_module.split("/")
        for length in range(len(parts), 0, -1):
            target = ReleaseTargetResolver._resolve_domain_target(
                project_root, "/".join(parts[:length])
            )
            if target:
                return target

        pom_file = os.path.join(project_root, selected_module, "pom.xml")
        if not os.path.isfile(pom_file) or not ReleaseTargetResolver._is_boot_service(pom_file):
            raise ValueError(f"仅支持发布独立服务域或可运行服务: {selected_module}")

        root = ReleaseTargetResolver._read_pom(pom_file)
        artifact_id = ReleaseTargetResolver._text(root, "mvn:artifactId")
        version = ReleaseTargetResolver._resolve_version(pom_file)
        if not artifact_id or not version:
            raise ValueError(f"无法解析可运行服务坐标: {selected_module}")
        return ReleaseTarget(
            domain_module=selected_module,
            deploy_module=selected_module,
            artifact_id=artifact_id,
            version=version,
            publish_maven=True,
            publish_docker=True,
        )

    @staticmethod
    def discover(project_root):
        targets = {}
        for dirpath, dirnames, filenames in os.walk(project_root):
            dirnames[:] = [
                name
                for name in dirnames
                if name not in {".git", ".idea", ".mvn", "target", "release_manager"}
            ]
            if "pom.xml" not in filenames:
                continue
            module = os.path.relpath(dirpath, project_root).replace(os.sep, "/")
            if module in {".", ""}:
                continue
            try:
                target = ReleaseTargetResolver.resolve(project_root, module)
            except (OSError, etree.XMLSyntaxError, ValueError):
                continue
            targets[target.domain_module] = target
        return sorted(targets.values(), key=lambda target: target.domain_module)
