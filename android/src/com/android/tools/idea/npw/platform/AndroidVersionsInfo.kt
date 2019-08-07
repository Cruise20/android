/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.npw.platform

import com.android.SdkConstants
import com.android.repository.api.ProgressIndicator
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoManager.RepoLoadedCallback
import com.android.repository.api.RepoPackage
import com.android.repository.api.UpdatablePackage
import com.android.repository.impl.meta.RepositoryPackages
import com.android.sdklib.AndroidTargetHash
import com.android.sdklib.AndroidVersion
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.SdkVersionInfo
import com.android.sdklib.SdkVersionInfo.HIGHEST_KNOWN_API
import com.android.sdklib.SdkVersionInfo.HIGHEST_KNOWN_STABLE_API
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.repository.IdDisplay
import com.android.sdklib.repository.meta.DetailsTypes
import com.android.sdklib.repository.meta.DetailsTypes.AddonDetailsType
import com.android.sdklib.repository.meta.DetailsTypes.ApiDetailsType
import com.android.sdklib.repository.meta.DetailsTypes.SysImgDetailsType
import com.android.sdklib.repository.targets.SystemImage
import com.android.tools.idea.gradle.npw.project.GradleBuildSettings.getRecommendedBuildToolsRevision
import com.android.tools.idea.npw.FormFactor
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.sdk.IdeSdks
import com.android.tools.idea.sdk.StudioDownloader
import com.android.tools.idea.sdk.StudioSettingsController
import com.android.tools.idea.sdk.progress.StudioLoggerProgressIndicator
import com.android.tools.idea.sdk.progress.StudioProgressRunner
import com.android.tools.idea.sdk.wizard.SdkQuickfixUtils
import com.android.tools.idea.sdk.wizard.SdkQuickfixUtils.PackageResolutionException
import com.android.tools.idea.templates.TemplateMetadata
import com.android.tools.idea.templates.TemplateUtils.knownVersions
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.io.File
import java.util.ArrayList
import java.util.Locale

/**
 * Lists the available Android Versions from local, remote, and statically-defined sources.
 * The list can be filtered by min sdk level and a callback mechanism allows information to be provided asynchronously.
 * It is also possible to query the list of packages that the system needs to install to satisfy the requirements of an API level.
 */
open class AndroidVersionsInfo { // open for Mockito
  /**
   * Call back interface to notify the caller that the requested items were loaded.
   * @see AndroidVersionsInfo.loadRemoteTargetVersions
   */
  interface ItemsLoaded {
    fun onDataLoadedFinished(items: List<VersionItem>)
  }

  private val myKnownTargetVersions: MutableList<VersionItem> = Lists.newArrayList() // All versions that we know about

  private val myInstalledVersions: MutableSet<AndroidVersion> = Sets.newHashSet()
  private var myHighestInstalledApiTarget: IAndroidTarget? = null
  /**
   * Load the list of known Android Versions. The list is made of Android Studio pre-known Android versions, and querying
   * the SDK manager for extra installed versions (can be third party SDKs). No remote network connection is needed.
   */
  fun loadLocalVersions() {
    loadLocalTargetVersions()
    loadInstalledVersions()
  }

  /**
   * Gets the list of known Android versions. The list can be loaded by calling
   * [loadLocalVersions] and/or [loadRemoteTargetVersions].
   */
  fun getKnownTargetVersions(formFactor: FormFactor, minSdkLevel: Int): MutableList<VersionItem> {
    val minSdkLevel = minSdkLevel.coerceAtLeast(formFactor.minOfflineApiLevel)
    val versionItemList: MutableList<VersionItem> = ArrayList()
    for (target in myKnownTargetVersions) {
      if (isFormFactorAvailable(formFactor, minSdkLevel, target.minApiLevel) ||
          target.androidTarget != null && target.androidTarget!!.version.isPreview) {
        versionItemList.add(target)
      }
    }
    return versionItemList
  }

  /**
   * Load the installed android versions from the installed SDK. No network connection needed.
   */
  private fun loadInstalledVersions() {
    myInstalledVersions.clear()
    var highestInstalledTarget: IAndroidTarget? = null
    for (target in loadInstalledCompilationTargets()) {
      if (target.isPlatform && target.version.featureLevel >= SdkVersionInfo.LOWEST_COMPILE_SDK_VERSION &&
          (highestInstalledTarget == null || target.version.featureLevel > highestInstalledTarget.version.featureLevel &&
           !target.version.isPreview)) {
        highestInstalledTarget = target
      }
      if (target.version.isPreview || target.additionalLibraries.isNotEmpty()) {
        myInstalledVersions.add(target.version)
      }
    }
    myHighestInstalledApiTarget = highestInstalledTarget
  }

  @VisibleForTesting
  open val highestInstalledVersion: AndroidVersion? // open for mockito
    get() = if (myHighestInstalledApiTarget == null) null else myHighestInstalledApiTarget!!.version

  fun loadInstallPackageList(installItems: List<VersionItem>): List<UpdatablePackage> {
    val requestedPaths: MutableSet<String> = Sets.newHashSet()
    val sdkHandler = AndroidSdks.getInstance().tryToChooseSdkHandler()

    // Install build tools, if not already installed
    requestedPaths.add(DetailsTypes.getBuildToolsPath(getRecommendedBuildToolsRevision(sdkHandler, REPO_LOG)))
    for (versionItem in installItems) {
      val androidVersion = versionItem.myAndroidVersion
      val platformPath = DetailsTypes.getPlatformPath(androidVersion)

      // Check to see if this is installed. If not, request that we install it
      if (versionItem.addon != null) {
        // The user selected a non platform SDK (e.g. for Google Glass). Let us install it:
        requestedPaths.add(versionItem.addon!!.path)

        // We also need the platform if not already installed:
        val targetManager = sdkHandler.getAndroidTargetManager(REPO_LOG)
        if (targetManager.getTargetFromHashString(AndroidTargetHash.getPlatformHashString(androidVersion), REPO_LOG) == null) {
          requestedPaths.add(platformPath)
        }
      }
      else {
        // TODO: If the user has no APIs installed that are at least of api level LOWEST_COMPILE_SDK_VERSION,
        // then we request (for now) to install HIGHEST_KNOWN_STABLE_API.
        // Instead, we should choose to install the highest stable API possible. However, users having no SDK at all installed is pretty
        // unlikely, so this logic can wait for a followup CL.
        if (myHighestInstalledApiTarget == null ||
            androidVersion.apiLevel > myHighestInstalledApiTarget!!.version.apiLevel &&
            !myInstalledVersions.contains(androidVersion)) {

          // Let us install the HIGHEST_KNOWN_STABLE_API.
          requestedPaths.add(DetailsTypes.getPlatformPath(AndroidVersion(HIGHEST_KNOWN_STABLE_API, null)))
        }
      }
    }
    return getPackageList(requestedPaths, sdkHandler)
  }

  /**
   * Get the list of versions, notably by populating the available values from local, remote, and statically-defined sources.
   */
  fun loadRemoteTargetVersions(formFactor: FormFactor,
                               minSdkLevel: Int,
                               itemsLoadedCallback: ItemsLoaded) {
    val minSdkLevel = minSdkLevel.coerceAtLeast(formFactor.minOfflineApiLevel)
    val versionItemList = getKnownTargetVersions(formFactor, minSdkLevel)
    loadRemoteTargetVersions(formFactor, minSdkLevel, versionItemList, itemsLoadedCallback)
  }

  /**
   * Load the local definitions of the android compilation targets.
   */
  private fun loadLocalTargetVersions() {
    myKnownTargetVersions.clear()
    if (AndroidSdkUtils.isAndroidSdkAvailable()) {
      val knownVersions = knownVersions
      for (i in knownVersions.indices) {
        myKnownTargetVersions.add(VersionItem(knownVersions[i], i + 1))
      }
    }
    for (target in loadInstalledCompilationTargets()) {
      if (target.version.isPreview || target.additionalLibraries.isNotEmpty()) {
        myKnownTargetVersions.add(VersionItem(target))
      }
    }
  }

  private fun loadRemoteTargetVersions(myFormFactor: FormFactor,
                                       minSdkLevel: Int,
                                       versionItemList: MutableList<VersionItem>,
                                       completedCallback: ItemsLoaded?) {
    val sdkHandler = AndroidSdks.getInstance().tryToChooseSdkHandler()
    val runCallbacks = Runnable {
      completedCallback?.onDataLoadedFinished(versionItemList)
    }
    val onComplete = RepoLoadedCallback { packages: RepositoryPackages ->
      ApplicationManager.getApplication().invokeLater(
        {
          addPackages(myFormFactor, versionItemList, packages.newPkgs, minSdkLevel)
          addOfflineLevels(myFormFactor, versionItemList)
          runCallbacks.run()
        }, ModalityState.any())
    }

    // We need to pick up addons that don't have a target created due to the base platform not being installed.
    val onLocalComplete = RepoLoadedCallback { packages: RepositoryPackages ->
      ApplicationManager.getApplication().invokeLater(
        { addPackages(myFormFactor, versionItemList, packages.localPackages.values, minSdkLevel) },
        ModalityState.any())
    }
    val onError = Runnable {
      ApplicationManager.getApplication().invokeLater(
        {
          addOfflineLevels(myFormFactor, versionItemList)
          runCallbacks.run()
        }, ModalityState.any())
    }
    val runner = StudioProgressRunner(false, false, "Refreshing Targets", null)
    sdkHandler.getSdkManager(REPO_LOG).load(
      RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      ImmutableList.of(onLocalComplete),
      ImmutableList.of(onComplete),
      ImmutableList.of(onError),
      runner, StudioDownloader(), StudioSettingsController.getInstance(), false)
  }

  private fun addPackages(myFormFactor: FormFactor,
                          versionItemList: MutableList<VersionItem>,
                          packages: Collection<RepoPackage?>,
                          minSdkLevel: Int) {
    val sorted = packages.asSequence()
      .filterNotNull()
      .filter { filterPkgDesc(it, myFormFactor, minSdkLevel) }
      .sortedBy { getAndroidVersion(it) }
    var existingApiLevel = -1
    var prevInsertedApiLevel = -1
    var index = -1
    for (info in sorted) {
      val apiLevel = getFeatureLevel(info)
      while (apiLevel > existingApiLevel) {
        existingApiLevel = if (++index < versionItemList.size) versionItemList[index].minApiLevel else Integer.MAX_VALUE
      }
      if (apiLevel != existingApiLevel && apiLevel != prevInsertedApiLevel) {
        versionItemList.add(index++, VersionItem(info))
        prevInsertedApiLevel = apiLevel
      }
    }
  }

  private fun addOfflineLevels(myFormFactor: FormFactor,
                               versionItemList: MutableList<VersionItem>) {
    var existingApiLevel = -1
    var prevInsertedApiLevel = -1
    var index = -1
    for (apiLevel in myFormFactor.minOfflineApiLevel..myFormFactor.maxOfflineApiLevel) {
      if (myFormFactor.isSupported(null, apiLevel) || apiLevel <= 0) {
        continue
      }
      while (apiLevel > existingApiLevel) {
        existingApiLevel = if (++index < versionItemList.size) versionItemList[index].minApiLevel else Integer.MAX_VALUE
      }
      if (apiLevel != existingApiLevel && apiLevel != prevInsertedApiLevel) {
        versionItemList.add(index++, VersionItem(apiLevel))
        prevInsertedApiLevel = apiLevel
      }
    }
  }

  inner class VersionItem {
    val myAndroidVersion: AndroidVersion
    val label: String
    val minApiLevel: Int
    val minApiLevelStr: String // Can be a number or a Code Name (eg "L", "N", etc)
    var androidTarget: IAndroidTarget? = null
      private set
    // TODO: We may no longer need this, as we only show Google Glass if we have it already installed
    var addon: RemotePackage? = null
      private set

    internal constructor(androidVersion: AndroidVersion, tag: IdDisplay, target: IAndroidTarget?) {
      myAndroidVersion = androidVersion
      label = getLabel(androidVersion, tag, target)
      androidTarget = target
      minApiLevel = androidVersion.featureLevel
      minApiLevelStr = androidVersion.apiString
    }

    internal constructor(label: String, minApiLevel: Int) {
      myAndroidVersion = AndroidVersion(minApiLevel, null)
      this.label = label
      this.minApiLevel = minApiLevel
      minApiLevelStr = minApiLevel.toString()
    }

    @VisibleForTesting
    constructor(minApiLevel: Int) : this(AndroidVersion(minApiLevel, null), SystemImage.DEFAULT_TAG, null)

    @VisibleForTesting
    constructor(target: IAndroidTarget) : this(target.version, SystemImage.DEFAULT_TAG, target)

    @VisibleForTesting
    constructor(info: RepoPackage) : this(getAndroidVersion(info), getTag(info)!!, null) {
      if (info is RemotePackage && SystemImage.GLASS_TAG == getTag(info)) {
        // If this is Glass then prepare to install this add-on package.
        // All platform are installed by a different mechanism.
        addon = info
      }
    }

    val buildApiLevel: Int
      get() = if (addon != null || androidTarget != null && (androidTarget!!.version.isPreview || !androidTarget!!.isPlatform)) {
          minApiLevel
        }
        else if (highestInstalledVersion != null && highestInstalledVersion!!.featureLevel > HIGHEST_KNOWN_STABLE_API) {
          highestInstalledVersion!!.featureLevel
        }
        else {
          HIGHEST_KNOWN_STABLE_API
        }

    val buildApiLevelStr: String
      get() {
        if (androidTarget == null) {
          return buildApiLevel.toString()
        }
        return if (androidTarget!!.isPlatform) {
          TemplateMetadata.getBuildApiString(androidTarget!!.version)
        }
        else AndroidTargetHash.getTargetHashString(androidTarget!!)
      }

    val targetApiLevel: Int
      get() = buildApiLevel

    val targetApiLevelStr: String
      get() {
        val buildApiLevel = buildApiLevel
        if (buildApiLevel >= HIGHEST_KNOWN_API || androidTarget != null && androidTarget!!.version.isPreview) {
          return if (androidTarget == null) Integer.toString(buildApiLevel) else androidTarget!!.version.apiString
        }
        val installedVersion = highestInstalledVersion
        return if (installedVersion != null && installedVersion.featureLevel == buildApiLevel) {
          installedVersion.apiString
        }
        else buildApiLevel.toString()
      }

    override fun equals(other: Any?): Boolean {
      return other is VersionItem && other.label == label
    }

    override fun hashCode(): Int {
      return label.hashCode()
    }

    private fun getLabel(version: AndroidVersion,
                         tag: IdDisplay?,
                         target: IAndroidTarget?): String {
      val featureLevel = version.featureLevel
      if (SystemImage.GLASS_TAG == tag) {
        return String.format(Locale.US, "Glass Development Kit Preview (API %1\$d)", featureLevel)
      }
      return if (featureLevel <= HIGHEST_KNOWN_API) {
        if (version.isPreview) {
          String.format("API %1\$s: Android %2\$s (%3\$s preview)",
                        SdkVersionInfo.getCodeName(featureLevel),
                        SdkVersionInfo.getVersionString(featureLevel),
                        SdkVersionInfo.getCodeName(featureLevel))
        }
        else if (target == null || target.isPlatform) {
          SdkVersionInfo.getAndroidName(featureLevel)
        }
        else {
          AndroidTargetHash.getTargetHashString(target)
        }
      }
      else {
        if (version.isPreview) {
          String.format(Locale.US, "API %1\$d: Android (%2\$s)", featureLevel, version.codename)
        }
        else {
          String.format(Locale.US, "API %1\$d: Android", featureLevel)
        }
      }
    }

    override fun toString(): String {
      return label
    }
  }

  companion object {
    private val REPO_LOG: ProgressIndicator = StudioLoggerProgressIndicator(AndroidVersionsInfo::class.java)
    private val NO_MATCH: IdDisplay = IdDisplay.create("no_match", "No Match")
    @get:JvmStatic
    val sdkManagerLocalPath: File?
      get() = IdeSdks.getInstance().androidSdkPath

    /**
     * @return a list of android compilation targets (platforms and add-on SDKs)
     */
    private fun loadInstalledCompilationTargets(): Array<IAndroidTarget> {
      val targetManager = AndroidSdks.getInstance().tryToChooseSdkHandler().getAndroidTargetManager(
        REPO_LOG)
      val result: MutableList<IAndroidTarget> = Lists.newArrayList()
      for (target in targetManager.getTargets(REPO_LOG)) {
        if (target.isPlatform || !target.additionalLibraries.isEmpty()) {
          result.add(target)
        }
      }
      return result.toTypedArray()
    }

    private fun getPackageList(requestedPaths: Collection<String>,
                               sdkHandler: AndroidSdkHandler): List<UpdatablePackage> {
      val requestedPackages: MutableList<UpdatablePackage> = ArrayList()
      val packages = sdkHandler.getSdkManager(
        REPO_LOG).packages
      val consolidated: Map<String, UpdatablePackage> = packages.consolidatedPkgs
      for (path in requestedPaths) {
        val p = consolidated[path]
        if (p != null && p.hasRemote()) {
          requestedPackages.add(p)
        }
      }
      var resolvedPackages: List<UpdatablePackage> = ArrayList()
      try {
        resolvedPackages = SdkQuickfixUtils.resolve(requestedPackages, packages)
      }
      catch (e: PackageResolutionException) {
        REPO_LOG.logError("Error Resolving Packages", e)
      }
      return resolvedPackages
    }

    private fun filterPkgDesc(p: RepoPackage, formFactor: FormFactor, minSdkLevel: Int): Boolean {
      return isApiType(p) && doFilter(formFactor, minSdkLevel, getTag(p), getFeatureLevel(p))
    }

    private fun doFilter(formFactor: FormFactor, minSdkLevel: Int, tag: IdDisplay?, targetSdkLevel: Int): Boolean {
      return formFactor.isSupported(tag, targetSdkLevel) && targetSdkLevel >= minSdkLevel
    }

    private fun isApiType(repoPackage: RepoPackage): Boolean {
      return repoPackage.typeDetails is ApiDetailsType
    }

    private fun getFeatureLevel(repoPackage: RepoPackage): Int {
      return getAndroidVersion(repoPackage).featureLevel
    }

    private fun isFormFactorAvailable(formFactor: FormFactor, minSdkLevel: Int, targetSdkLevel: Int): Boolean {
      return doFilter(formFactor, minSdkLevel, SystemImage.DEFAULT_TAG, targetSdkLevel)
    }

    private fun getAndroidVersion(repoPackage: RepoPackage): AndroidVersion {
      val details = repoPackage.typeDetails
      if (details is ApiDetailsType) {
        return (details as ApiDetailsType).androidVersion
      }
      throw RuntimeException("Could not determine version")
    }

    /**
     * Return the tag for the specified repository package.
     * We are only interested in 2 package types.
     */
    private fun getTag(repoPackage: RepoPackage): IdDisplay? {
      val details = repoPackage.typeDetails
      var tag = NO_MATCH
      if (details is AddonDetailsType) {
        tag = (details as AddonDetailsType).tag
      }
      if (details is SysImgDetailsType) {
        val imgDetailsType = details as SysImgDetailsType
        if (imgDetailsType.abi == SdkConstants.CPU_ARCH_INTEL_ATOM) {
          tag = imgDetailsType.tag
        }
      }
      return tag
    }
  }
}