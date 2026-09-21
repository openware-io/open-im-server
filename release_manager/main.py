from core.environment_check import setup_environment

# 在启动应用程序前检查并安装依赖包
setup_environment()
from PySide6.QtWidgets import QApplication
from PySide6.QtGui import QIcon
from ui.main_window import MainWindow
import sys
import os


def main():
    app = QApplication(sys.argv)
    window = MainWindow()

    # 设置应用程序图标
    icon_path = os.path.join("resources", "icons", "icon.png")
    if os.path.exists(icon_path):
        app.setWindowIcon(QIcon(icon_path))
        window.setWindowIcon(QIcon(icon_path))

    # 获取屏幕几何信息
    screen = app.primaryScreen().geometry()
    # 获取窗口几何信息
    window_geometry = window.geometry()

    # 计算水平居中位置，垂直位置设为50(距离顶部50像素)
    x = (screen.width() - window_geometry.width()) // 2
    y = 50

    # 移动窗口到指定位置
    window.move(x, y)

    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
