package org.springsource.ide.eclipse.gradle.core;

import java.io.File;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseProjectDependency;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.GradleClassPathContainer;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.MarkerMaker;
import org.springsource.ide.eclipse.gradle.core.ivy.IvyUtils;
import org.springsource.ide.eclipse.gradle.core.m2e.M2EUtils;
import org.springsource.ide.eclipse.gradle.core.util.WorkspaceUtil;

/**
 * An instance of this class computes a projects classpath from a model obtained from the ToolingApi.
 * Well, actually, at the moment it only deals with entries returned from the model's getClasspath method.
 * This only provides 'external' dependencies. Not project dependencies. 
 */
public class GradleDependencyComputer {

	public static boolean DEBUG = (""+Platform.getLocation()).equals("/tmp/testws");
	
	public void debug(String msg) {
		if (DEBUG) {
			System.out.println("ClassPathComputer "+project+" : "+msg);
		}
	}
	
	private GradleProject project;
	private ClassPath classpath; // computed classpath or null if not yet computed.
	private EclipseProject gradleModel; // The model that was used to compute the current classpath. We use this to check if we need to recompute the classpath.
	
	public GradleDependencyComputer(GradleProject project) {
		this.project = project;
		this.classpath = null;
		this.gradleModel = null;
	}
	
	public ClassPath getClassPath(EclipseProject gradleModel) {
		if (classpath==null || !gradleModel.equals(this.gradleModel)) {
			this.gradleModel = gradleModel;
			classpath = computeEntries();
		}
		return classpath;
	}
	
	private ClassPath computeEntries() {
		MarkerMaker markers = new MarkerMaker(project, GradleClassPathContainer.ERROR_MARKER_ID);
		try {
			debug("gradleModel ready: "+Integer.toHexString(System.identityHashCode(gradleModel))+" "+gradleModel);
			classpath = new ClassPath(project);
			
			for (ExternalDependency gEntry : gradleModel.getClasspath()) {
				// Get the location of the jar itself
				File file = gEntry.getFile();
				IPath jarPath = new Path(file.getAbsolutePath()); 
				if (jarPath.lastSegment()!=null && jarPath.lastSegment().endsWith(".jar")) {
					IProject projectDep = null;
					if (project.getProjectPreferences().getRemapJarsToMavenProjects())
						projectDep = M2EUtils.getMavenProject(gEntry);
					if (project.getProjectPreferences().getRemapJarsToIvyProjects())
						projectDep = IvyUtils.getIvyProject(gEntry);
					
					if (projectDep != null)
						classpath.addProjectDependency(projectDep);
					else
						classpath.addJarEntry(gEntry);
				} else {
					//'non jar' entries may happen when project has a dependency on a sourceSet's output folder.
					//See http://issues.gradle.org/browse/GRADLE-1766
					boolean isCovered = false;
					IProject containingProject = WorkspaceUtil.getContainingProject(file);
					if (containingProject!=null) {
						GradleProject referredProject = GradleCore.create(containingProject);
						if (referredProject!=null) {
							if (project.conservativeDependsOn(referredProject, false)) {
								//We assume that a project dependeny is adequate representation for the dependency on 
								//an output folder in the project.
								isCovered = true;
							}
						}
					}
					if (!isCovered) {
						if (file.exists()) {
							//We don't know what this is, but it exists. Give it the benefit of the doubt and try to treat it 
							//the same as a 'jar'... pray... and hope for the best. 
							//Note: one possible thing this could be is an output folder containing .class files.
							classpath.addJarEntry(gEntry);
							markers.reportWarning("Unknown type of Gradle Dependency was treated as 'jar' entry: "+jarPath);
						} else {
							//Adding this will cause very wonky behavior see: https://issuetracker.springsource.com/browse/STS-2531
							//So do not add it. Only report it as an error marker on the project.
							markers.reportError("Illegal entry in Gradle Dependencies: "+jarPath);
						}
					}
				}
			}
			
			for (EclipseProjectDependency dep : gradleModel.getProjectDependencies()) {
				IProject workspaceProject = GradleCore.create(dep.getTargetProject()).getProject();
				if(workspaceProject != null && workspaceProject.isOpen())
					classpath.addProjectDependency(GradleCore.create(dep.getTargetProject()).getProject());
				else {
					ExternalDependency libraryEquivalent = IvyUtils.getLibraryEquivalent(dep.getTargetProject());
					if(libraryEquivalent != null)
						classpath.addJarEntry(libraryEquivalent); 
				}
			}
			
			return classpath;
		} finally {
			markers.schedule();
		}
	}
}
