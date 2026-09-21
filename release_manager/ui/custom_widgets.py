from PySide6.QtCore import Qt, QPoint
from PySide6.QtGui import QKeyEvent
from PySide6.QtWidgets import QListWidget, QLabel


class SearchableListWidget(QListWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.search_text = ""
        self.search_timer = None
        self.setFocusPolicy(Qt.StrongFocus)

        # 创建悬浮搜索提示框
        self.search_hint = QLabel(parent=self)
        self.search_hint.setStyleSheet(
            """
            QLabel {
                background-color: #2a2a2a;
                color: #FFA500;
                border: 2px solid #FFA500;
                border-radius: 5px;
                padding: 5px;
                font-size: 14px;
            }
        """
        )
        self.search_hint.hide()

    def keyPressEvent(self, event: QKeyEvent):
        """处理键盘输入事件"""
        if event.key() == Qt.Key_Escape:
            # ESC键清除搜索
            self.search_text = ""
            self.clearSelection()
            self.show_all_items()
            self.search_hint.hide()
        elif event.key() == Qt.Key_Backspace:
            # 退格键删除最后一个字符
            self.search_text = self.search_text[:-1]
            self.update_search_hint()
            self.search_items()
        elif event.text() and event.text().isprintable():
            # 可打印字符添加到搜索文本
            self.search_text += event.text().lower()
            self.update_search_hint()
            self.search_items()
        else:
            # 其他按键交给父类处理
            super().keyPressEvent(event)

    def update_search_hint(self):
        """更新搜索提示框"""
        if self.search_text:
            self.search_hint.setText(f"搜索: {self.search_text}")
            self.search_hint.adjustSize()

            # 计算提示框位置 - 显示在列表框的右上角
            hint_pos = QPoint(
                self.width() - self.search_hint.width() - 10,  # 右边距离10像素
                10,  # 顶部距离10像素
            )
            self.search_hint.move(hint_pos)
            self.search_hint.show()
        else:
            self.search_hint.hide()

    def search_items(self):
        """根据搜索文本过滤项目"""
        first_match = None
        for index in range(self.count()):
            item = self.item(index)
            if self.search_text in item.text().lower():
                item.setHidden(False)
                if first_match is None:
                    first_match = item
            else:
                item.setHidden(True)

        # 选中第一个匹配项
        if first_match:
            self.setCurrentItem(first_match)

    def show_all_items(self):
        """显示所有项目"""
        for index in range(self.count()):
            self.item(index).setHidden(False)

    def focusOutEvent(self, event):
        """失去焦点时隐藏搜索提示框"""
        super().focusOutEvent(event)
        self.search_hint.hide()
        self.search_text = ""
        self.show_all_items()
