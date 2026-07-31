"""pytest 全局配置：把 agent 根目录加入 sys.path，使 core/api/config 等模块可直接导入"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
