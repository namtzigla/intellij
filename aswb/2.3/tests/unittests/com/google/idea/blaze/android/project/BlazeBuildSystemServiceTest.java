/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.project;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.android.tools.idea.project.BuildSystemService;
import com.google.common.collect.Maps;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.AndroidResourceModuleRegistry;
import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.actions.BlazeBuildService;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.ideinfo.TargetMapBuilder;
import com.google.idea.blaze.base.lang.buildfile.references.BuildReferenceManager;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.projectview.ProjectViewManager;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager;
import com.google.idea.blaze.base.sync.BlazeSyncManager;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin.ModuleEditor;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.base.sync.workspace.WorkspacePathResolver;
import com.intellij.mock.MockModule;
import com.intellij.mock.MockVirtualFile;
import com.intellij.openapi.editor.LazyRangeMarkerFactory;
import com.intellij.openapi.editor.impl.LazyRangeMarkerFactoryImpl;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.ex.temp.TempFileSystem;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import java.io.File;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Test cases for {@link BlazeBuildSystemService}. */
@RunWith(JUnit4.class)
public class BlazeBuildSystemServiceTest extends BlazeTestCase {
  Module module;
  BuildSystemService service;

  @Override
  protected void initTest(Container applicationServices, Container projectServices) {
    module = new MockModule(project, () -> {});

    mockBlazeImportSettings(projectServices); // For Blaze.isBlazeProject.
    createMocksForBuildProject(applicationServices);
    createMocksForSyncProject(projectServices);
    createMocksForAddDependency(applicationServices, projectServices);

    ExtensionPoint<BuildSystemService> extensionPoint =
        registerExtensionPoint(
            ExtensionPointName.create("com.android.project.buildSystemService"),
            BuildSystemService.class);
    extensionPoint.registerExtension(new BlazeBuildSystemService());

    service = BuildSystemService.getInstance(project);
  }

  @Test
  public void testIsBlazeBuildSystemService() {
    assertThat(service).isInstanceOf(BlazeBuildSystemService.class);
  }

  @Test
  public void testBuildProject() {
    service.buildProject(project);
    verify(BlazeBuildService.getInstance()).buildProject(project);
    verifyNoMoreInteractions(BlazeBuildService.getInstance());
  }

  @Test
  public void testSyncProject() {
    service.syncProject(project);
    verify(BlazeSyncManager.getInstance(project)).incrementalProjectSync();
    verifyNoMoreInteractions(BlazeSyncManager.getInstance(project));
  }

  @Test
  public void testAddDependencyWithBuildTargetPsi() throws Exception {
    PsiElement buildTargetPsi = mock(PsiElement.class);
    PsiFile psiFile = mock(PsiFile.class);

    BuildReferenceManager buildReferenceManager = BuildReferenceManager.getInstance(project);
    when(buildReferenceManager.resolveLabel(new Label("//foo:bar"))).thenReturn(buildTargetPsi);
    when(buildTargetPsi.getContainingFile()).thenReturn(psiFile);
    when(buildTargetPsi.getTextOffset()).thenReturn(1337);

    VirtualFile buildFile = TempFileSystem.getInstance().findFileByPath("/foo/BUILD");
    assertThat(buildFile).isNotNull();
    when(psiFile.getVirtualFile()).thenReturn(buildFile);

    String dependency = "com.android.foo:bar"; // Doesn't matter.

    service.addDependency(module, dependency);

    ArgumentCaptor<OpenFileDescriptor> descriptorCaptor =
        ArgumentCaptor.forClass(OpenFileDescriptor.class);
    verify(FileEditorManager.getInstance(project))
        .openTextEditor(descriptorCaptor.capture(), eq(true));
    OpenFileDescriptor descriptor = descriptorCaptor.getValue();
    assertThat(descriptor.getProject()).isEqualTo(project);
    assertThat(descriptor.getFile()).isEqualTo(buildFile);
    assertThat(descriptor.getOffset()).isEqualTo(1337);
    verifyNoMoreInteractions(FileEditorManager.getInstance(project));
  }

  @Test
  public void testAddDependencyWithoutBuildTargetPsi() throws Exception {
    // Can't find PSI for the target.
    when(BuildReferenceManager.getInstance(project).resolveLabel(new Label("//foo:bar")))
        .thenReturn(null);

    VirtualFile buildFile = TempFileSystem.getInstance().findFileByPath("/foo/BUILD");
    assertThat(buildFile).isNotNull();

    String dependency = "com.android.foo:bar"; // Doesn't matter.

    service.addDependency(module, dependency);

    verify(FileEditorManager.getInstance(project)).openFile(buildFile, true);
    verifyNoMoreInteractions(FileEditorManager.getInstance(project));
  }

  private void mockBlazeImportSettings(Container projectServices) {
    BlazeImportSettingsManager importSettingsManager = new BlazeImportSettingsManager(project);
    importSettingsManager.setImportSettings(
        new BlazeImportSettings("", "", "", "", "", Blaze.BuildSystem.Blaze));
    projectServices.register(BlazeImportSettingsManager.class, importSettingsManager);
  }

  private static void createMocksForBuildProject(Container applicationServices) {
    applicationServices.register(BlazeBuildService.class, mock(BlazeBuildService.class));
  }

  private static void createMocksForSyncProject(Container projectServices) {
    projectServices.register(ProjectViewManager.class, new MockProjectViewManager());
    projectServices.register(BlazeSyncManager.class, mock(BlazeSyncManager.class));
  }

  private void createMocksForAddDependency(
      Container applicationServices, Container projectServices) {
    projectServices.register(BlazeProjectDataManager.class, new MockProjectDataManager());
    projectServices.register(FileEditorManager.class, mock(FileEditorManager.class));
    projectServices.register(BuildReferenceManager.class, mock(BuildReferenceManager.class));
    projectServices.register(LazyRangeMarkerFactory.class, mock(LazyRangeMarkerFactoryImpl.class));

    applicationServices.register(TempFileSystem.class, new MockFileSystem("/foo/BUILD"));

    AndroidResourceModuleRegistry moduleRegistry = new AndroidResourceModuleRegistry();
    moduleRegistry.put(
        module,
        AndroidResourceModule.builder(TargetKey.forPlainTarget(new Label("//foo:bar"))).build());
    projectServices.register(AndroidResourceModuleRegistry.class, moduleRegistry);
  }

  private static class MockProjectViewManager extends ProjectViewManager {
    private ProjectViewSet viewSet;

    public MockProjectViewManager() {
      this.viewSet = ProjectViewSet.builder().build();
    }

    @Nullable
    @Override
    public ProjectViewSet getProjectViewSet() {
      return viewSet;
    }

    @Nullable
    @Override
    public ProjectViewSet reloadProjectView(
        BlazeContext context, WorkspacePathResolver workspacePathResolver) {
      return viewSet;
    }
  }

  private static class MockProjectDataManager implements BlazeProjectDataManager {
    private BlazeProjectData projectData;

    public MockProjectDataManager() {
      TargetMap targetMap =
          TargetMapBuilder.builder()
              .addTarget(
                  TargetIdeInfo.builder()
                      .setLabel(new Label("//foo:bar"))
                      .setBuildFile(ArtifactLocation.builder().setRelativePath("foo/BUILD").build())
                      .build())
              .build();
      ArtifactLocationDecoder decoder = (location) -> new File("/", location.getRelativePath());

      projectData =
          new BlazeProjectData(0L, targetMap, null, null, null, null, decoder, null, null, null);
    }

    @Nullable
    @Override
    public BlazeProjectData getBlazeProjectData() {
      return projectData;
    }

    @Override
    public ModuleEditor editModules() {
      return null;
    }
  }

  private static class MockFileSystem extends TempFileSystem {
    private Map<String, VirtualFile> files;

    public MockFileSystem(String... paths) {
      files = Maps.newHashMap();
      for (String path : paths) {
        files.put(path, new MockVirtualFile(path));
      }
    }

    @Override
    public VirtualFile findFileByPath(String path) {
      return files.get(path);
    }

    @Override
    public VirtualFile findFileByIoFile(File file) {
      return findFileByPath(file.getPath());
    }
  }
}