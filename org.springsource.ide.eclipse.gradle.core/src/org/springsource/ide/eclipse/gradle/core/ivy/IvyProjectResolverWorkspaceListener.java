package org.springsource.ide.eclipse.gradle.core.ivy;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleProject;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.GradleClassPathContainer;

public class IvyProjectResolverWorkspaceListener implements IResourceChangeListener {
	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		try {
            if (event.getType() == IResourceChangeEvent.PRE_CLOSE || event.getType() == IResourceChangeEvent.PRE_DELETE) {
                IResource res = event.getResource();
                IProject project;
                switch (res.getType()) {
                    case IResource.FOLDER:
                        project = ((IFolder) res).getProject();
                        break;
                    case IResource.FILE:
                        project = ((IFile) res).getProject();
                        break;
                    case IResource.PROJECT:
                        project = (IProject) res;
                        break;
                    default:
                        return;
                }
                if (GradleCore.isGradleProject(project))
                	projectClosed(project);
            } else if (event.getType() == IResourceChangeEvent.POST_CHANGE) {
            	// Find out if a project was opened.
                IResourceDelta delta = event.getDelta();
                if (delta == null) return;

                IResourceDelta[] projDeltas = delta.getAffectedChildren(IResourceDelta.CHANGED);
                for (int i = 0; i < projDeltas.length; ++i) {
                    IResourceDelta projDelta = projDeltas[i];
                    if ((projDelta.getFlags() & IResourceDelta.OPEN) == 0)
                        continue;
                    IResource resource = projDeltas[i].getResource();
                    if (!(resource instanceof IProject))
                        continue;

                    IProject project = (IProject) resource;
        			if (GradleCore.isGradleProject(project)) {
        				projectOpened(project);
        			}
                }
            }
        } catch (OperationCanceledException oce) {
        	// do nothing
        }
	}
	
	private void projectClosed(final IProject project) {
		new Job("Closing Gradle project " + project.getName()) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Closing Gradle project" + project.getName(), IProgressMonitor.UNKNOWN);
				for (GradleProject gradleProject : GradleCore.getGradleProjects()) {
					if(gradleProject.getProject().equals(project))
						continue;
					GradleClassPathContainer cpContainer = gradleProject.getClassPathcontainer();
					if(!cpContainer.isInitialized()) {
						// prevents error related to project reference not found on uninitialized container after the swap
						cpContainer.notifyJDT();
					}
					if(gradleProject.getDependencyComputer().getClassPath(monitor).swapWithLibrary(project))
						cpContainer.update();
				}
				monitor.done();
				return Status.OK_STATUS;
			}
		}.schedule();
    }
	
	private void projectOpened(final IProject project) {
		new Job("Opening Gradle project " + project.getName()) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Opening Gradle project" + project.getName(), IProgressMonitor.UNKNOWN);
				for (GradleProject gradleProject : GradleCore.getGradleProjects()) {
					if(gradleProject.getProject().equals(project))
						continue;
					GradleClassPathContainer cpContainer = gradleProject.getClassPathcontainer();
					if(!cpContainer.isInitialized()) {
						// prevents error related to project reference not found on uninitialized container after the swap
						cpContainer.notifyJDT(); 
					}
					if(gradleProject.getDependencyComputer().getClassPath(monitor).swapWithProject(project))
						cpContainer.update();
				}
				monitor.done();
				return Status.OK_STATUS;
			}
		}.schedule();
    }
}
