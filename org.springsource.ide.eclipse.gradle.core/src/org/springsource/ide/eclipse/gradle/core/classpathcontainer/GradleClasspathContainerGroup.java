package org.springsource.ide.eclipse.gradle.core.classpathcontainer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.springsource.ide.eclipse.gradle.core.ClassPath;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleProject;
import org.springsource.ide.eclipse.gradle.core.wtp.WTPUtil;

public class GradleClasspathContainerGroup {
	private static Job job;
	
	/**
	 * Adds a {@link GradleClassPathContainer} entry to the project's classpath.
	 * @param mon 
	 * @throws JavaModelException 
	 */
	public static void addTo(IJavaProject project, IProgressMonitor mon) throws JavaModelException {
		mon.beginTask("Add classpath container", 10);
		try {
			mon.worked(1);
			if (!isOnClassPath(project)) {
				boolean export = GradleCore.getInstance().getPreferences().isExportDependencies();
				
				//Only add it if it isn't there yet
				GradleProject gradleProject = GradleCore.create(project);
				
				ClassPath classpath = new ClassPath(gradleProject, project.getRawClasspath());
				classpath.add(WTPUtil.addToDeploymentAssembly(project, JavaCore.newContainerEntry(new Path(GradleClassPathContainer.ID), export)));
				classpath.add(WTPUtil.addToDeploymentAssembly(project, JavaCore.newContainerEntry(new Path(GradleProjectClassPathContainer.ID), export)));
				
				classpath.setOn(project, new SubProgressMonitor(mon, 9));
				
				gradleProject.getClassPathContainer().clearClasspath();
				gradleProject.getProjectClassPathContainer().clearClasspath();
			}
		} finally {
			mon.done();
		}
	}
	
	/**
	 * Returns true if the {@link GradleClassPathContainer} is on the project's classpath.
	 */
	public static boolean isOnClassPath(IJavaProject project) {
		try {
			IClasspathEntry[] classpath = project.getRawClasspath();
			for (IClasspathEntry e : classpath) {
				if (isGradleContainerEntry(e)) {
					return true;
				}
			}
		} catch (JavaModelException e) {
			GradleCore.log(e);
		}
		return false;
	}
	
	public static boolean isGradleContainerEntry(IClasspathEntry e) {
		if(e == null || e.getEntryKind() != IClasspathEntry.CPE_CONTAINER) return false;
		String eId = e.getPath().segment(0);
		return eId.equals(GradleProjectClassPathContainer.ID) || eId.equals(GradleClassPathContainer.ID);
	}
	
	/**
	 * Removes {@link GradleClassPathContainer} entries from the project's classpath.
	 */
	public static void removeFrom(IJavaProject javaProject, IProgressMonitor mon) throws JavaModelException {
		IClasspathEntry[] classpath = javaProject.getRawClasspath();
		ClassPath newClasspath = new ClassPath(GradleCore.create(javaProject));
		for (IClasspathEntry e : classpath)
			if (!isGradleContainerEntry(e))
				newClasspath.add(e);
		newClasspath.setOn(javaProject, mon);
		new MarkerMaker(GradleCore.create(javaProject), BaseGradleClasspathContainer.ERROR_MARKER_ID).schedule(); //This should erase any existing markers.
	}
	
	/**
	 * Request an asynchronous update of this class path container. This causes the class path container to
	 * return to uninitialised state and ensures a job is scheduled to initialise it later.
	 * It is acceptable to request multiple updates, only one update job should be scheduled.
	 * @return Reference to the job that is doing the update, in case caller cares to wait for it to finish.
	 */
	public static synchronized Job requestUpdate(final IProject project, boolean popupProgress) {
		if (job!=null) {
			return job; // A job is already scheduled. Don't schedule another job!
		}

		job = new Job("Update Gradle Classpath for "+ project.getName()) {

			@Override
			public IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Initializing Gradle Classpath Container", IProgressMonitor.UNKNOWN);
				
				
				try {
					GradleProject gradleProject = GradleCore.create(project);
					if(gradleProject != null) {
						// TODO JON is this what causes "refresh project model" every time?
						gradleProject.getGradleModel(monitor); // Forces initialisation of the model.
						
						gradleProject.getClassPathContainer().clearClasspath();
						gradleProject.getProjectClassPathContainer().clearClasspath();
						
						// Makes JDT get our class path initialiser to run again.
						if(gradleProject.getProjectClassPathContainer() != null)
							gradleProject.getProjectClassPathContainer().setJDTClassPathContainer();
						if(gradleProject.getClassPathContainer() != null)
							gradleProject.getClassPathContainer().setJDTClassPathContainer();
					}
					return Status.OK_STATUS;
				} catch (Exception e) {
					return new Status(IStatus.ERROR, GradleCore.PLUGIN_ID, "Error while initializing classpath container", e);
				} finally {
					monitor.done();
					job = null;
				}
			}
		};
		job.setUser(popupProgress);
		job.setPriority(Job.BUILD);
		job.setRule(ResourcesPlugin.getWorkspace().getRuleFactory().buildRule());
		job.schedule();
		return job;
	}
}
