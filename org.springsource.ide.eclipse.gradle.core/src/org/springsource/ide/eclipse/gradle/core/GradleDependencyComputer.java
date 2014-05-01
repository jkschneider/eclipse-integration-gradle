package org.springsource.ide.eclipse.gradle.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.core.ClasspathAttribute;
import org.eclipse.jdt.internal.core.ClasspathEntry;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseProjectDependency;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.GradleClassPathContainer;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.MarkerMaker;
import org.springsource.ide.eclipse.gradle.core.ivy.IvyUtils;
import org.springsource.ide.eclipse.gradle.core.m2e.M2EUtils;
import org.springsource.ide.eclipse.gradle.core.util.WorkspaceUtil;
import org.springsource.ide.eclipse.gradle.core.wtp.WTPUtil;

/**
 * An instance of this class computes a projects classpath from a model obtained from the ToolingApi.
 * Well, actually, at the moment it only deals with entries returned from the model's getClasspath method.
 * This only provides 'external' dependencies. Not project dependencies. 
 */
@SuppressWarnings("restriction")
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
	
	private void addJarEntry(IPath jarPath, ExternalDependency gEntry, boolean export) {
		// Get the location of a source jar, if any.
		IPath sourceJarPath = null;
		File sourceJarFile = gEntry.getSource();
		if (sourceJarFile!=null) {
			sourceJarPath = new Path(sourceJarFile.getAbsolutePath());
		}

		//Get the location of Java doc attachement, if any
		List<IClasspathAttribute> extraAttributes = new ArrayList<IClasspathAttribute>();
		File javaDoc = gEntry.getJavadoc();
		if (javaDoc!=null) {
			//Example of what it looks like in the eclipse .classpath file:
			//<attributes>
			//  <attribute name="javadoc_location" value="jar:file:/tmp/workspace/repos/test-with-jdoc-1.0-javadoc.jar!/"/>
			//</attributes>
			String jdoc = javaDoc.toURI().toString();
			if (!javaDoc.isDirectory()) {
				//Assume its a jar or zip containing the docs
				jdoc = "jar:"+jdoc+"!/";
			}
			IClasspathAttribute javaDocAttribute = new ClasspathAttribute(IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME, jdoc);
			extraAttributes.add(javaDocAttribute);
		}
		
		WTPUtil.excludeFromDeployment(project.getJavaProject(), jarPath, extraAttributes);


		//Create classpath entry with all this info
		IClasspathEntry newLibraryEntry = JavaCore.newLibraryEntry(
				jarPath, 
				sourceJarPath, 
				null, 
				ClasspathEntry.NO_ACCESS_RULES, 
				extraAttributes.toArray(new IClasspathAttribute[extraAttributes.size()]), 
				export);
		classpath.add(newLibraryEntry);
		if (newLibraryEntry.toString().contains("unresolved dependency")) {
			debug("entry: "+newLibraryEntry);
		}
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
			boolean export = GradleCore.getInstance().getPreferences().isExportDependencies(); //TODO: maybe should be project preference?
			
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
						addProjectDependency(projectDep, export);
					else
						addJarEntry(jarPath, gEntry, export);
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
							addJarEntry(jarPath, gEntry, export);
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
					addProjectDependency(GradleCore.create(dep.getTargetProject()).getProject(), export);
				else {
					ExternalDependency libraryEquivalent = IvyUtils.getLibraryEquivalent(dep);
					if(libraryEquivalent != null)
						addJarEntry(new Path(libraryEquivalent.getFile().getAbsolutePath()), libraryEquivalent, export); 
				}
			}
			
			return classpath;
		} finally {
			markers.schedule();
		}
	}

	private void addProjectDependency(IProject projectDep, boolean export) {
		classpath.add(JavaCore.newProjectEntry(projectDep.getFullPath(), export));
	}
}
