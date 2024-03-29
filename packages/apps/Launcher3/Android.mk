#
# Copyright (C) 2013 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)

#
# Build app code.
#
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-v4 \
    android-support-v7-recyclerview \
    com.mediatek.launcher3.ext

LOCAL_JAVA_LIBRARIES := mediatek-framework \
                        framework \
                        telephony-common \
                        mediatek-common

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
    $(call all-java-files-under, WallpaperPicker/src) \
    $(call all-proto-files-under, protos)

SRC_ROOT := src/com/android/launcher3

#OPTR_SPEC_SEG_DEF := OP09_SPEC0212_SEGDEFAULT

ifeq ($(strip $(OPTR_SPEC_SEG_DEF)),OP09_SPEC0212_SEGDEFAULT)
#$(error "OPTR_SPEC_SEG_DEF 1")
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/allapps/AllAppsContainerView.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/allapps/AllAppsGridAdapter.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/allapps/AllAppsRecyclerView.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/allapps/AlphabeticalAppsList.java, $(LOCAL_SRC_FILES))

LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/AllAppsListPluginEx.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/AppInfo.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/BaseRecyclerViewFastScrollBar.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/BubbleTextView.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/CellLayout.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/DragController.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/Folder.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/FolderIcon.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/FolderInfo.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/FolderPagedView.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/FolderPluginEx.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/HideAppsActivity.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/ItemInfo.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/Launcher.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/LauncherExtPlugin.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/LauncherModel.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/LauncherModelPluginEx.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/LauncherPluginEx.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/LauncherProvider.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/ShortcutInfo.java, $(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(filter-out $(SRC_ROOT)/Workspace.java, $(LOCAL_SRC_FILES))

LOCAL_SRC_FILES += $(call all-java-files-under, $(SRC_ROOT)/op09)
else
#$(error "OPTR_SPEC_SEG_DEF 2")
OP09_SRC := $(call all-java-files-under, $(SRC_ROOT)/op09)
LOCAL_SRC_FILES := $(filter-out $(OP09_SRC), $(LOCAL_SRC_FILES))
endif	
	
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/WallpaperPicker/res \
    $(LOCAL_PATH)/res \
    $(LOCAL_PATH)/../../../prebuilts/sdk/current/support/v7/recyclerview/res

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

LOCAL_PROTOC_OPTIMIZE_TYPE := nano
LOCAL_PROTOC_FLAGS := --proto_path=$(LOCAL_PATH)/protos/
LOCAL_AAPT_FLAGS := \
    --auto-add-overlay \
    --extra-packages android.support.v7.recyclerview

#LOCAL_SDK_VERSION := current
LOCAL_PACKAGE_NAME := Launcher3
LOCAL_PRIVILEGED_MODULE := true
LOCAL_CERTIFICATE := shared

LOCAL_OVERRIDES_PACKAGES := Home Launcher2

include $(BUILD_PACKAGE)


#
# Protocol Buffer Debug Utility in Java
#
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, util) \
    $(call all-proto-files-under, protos)

LOCAL_PROTOC_OPTIMIZE_TYPE := nano
LOCAL_PROTOC_FLAGS := --proto_path=$(LOCAL_PATH)/protos/

LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := launcher_protoutil_lib
LOCAL_IS_HOST_MODULE := true
LOCAL_JAR_MANIFEST := util/etc/manifest.txt

include $(BUILD_HOST_JAVA_LIBRARY)

#
# Protocol Buffer Debug Utility Wrapper Script
#
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := launcher_protoutil

include $(BUILD_SYSTEM)/base_rules.mk

$(LOCAL_BUILT_MODULE): | $(HOST_OUT_JAVA_LIBRARIES)/launcher_protoutil_lib.jar
$(LOCAL_BUILT_MODULE): $(LOCAL_PATH)/util/etc/launcher_protoutil | $(ACP)
	@echo "Copy: $(PRIVATE_MODULE) ($@)"
	$(copy-file-to-new-target)
	$(hide) chmod 755 $@

INTERNAL_DALVIK_MODULES += $(LOCAL_INSTALLED_MODULE)

include $(call all-makefiles-under,$(LOCAL_PATH))
