LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_DEVICE),R4)
include $(call all-subdir-makefiles,$(LOCAL_PATH))
endif
