package org.springsource.ide.eclipse.gradle.core.classpathcontainer;

import java.io.Serializable;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.JavaProject;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleProject;
import org.springsource.ide.eclipse.gradle.core.GradleSaveParticipant;

@SuppressWarnings("restriction")
public abstract class BaseGradleClasspathContainer implements IClasspathContainer {
	public static final String ERROR_MARKER_ID = "org.springsource.ide.eclipse.gradle.core.classpathcontainer";
	
	/**
	 * This field remembers what model the persisted entries came from (this will only
	 * be known if the entries where created during the current session since models
	 * themselves do not persist across sessions).
	 */
	protected EclipseProject oldModel = null;
	
	protected GradleProject project;
	protected IPath path;
	protected IClasspathEntry[] persistedEntries;
	private String id;
	private String baseDescription;
	
	public BaseGradleClasspathContainer(IPath path, GradleProject project, String id, String baseDescription) {
		this.project = project;
		this.path = path;
		this.id = id;
		this.baseDescription = baseDescription;
	}
	
	@Override 
	public int getKind() {
		return IClasspathContainer.K_APPLICATION;
	}

	@Override
	public IPath getPath() {
		return path;
	}
	
	protected IClasspathEntry[] getPersistedEntries() {
		if (persistedEntries!=null) {
			return persistedEntries;
		} else {
			IProject eclipseProject = project.getProject();
			GradleSaveParticipant store = GradleSaveParticipant.getInstance();
			return persistedEntries = decode(store.get(eclipseProject, id));
		}
	}
	
	/**
	 * This method may be used during initialisation, if the container is initialised using 
	 * a persisted set of entries from the previous run. Persisted entries will only be
	 * returned if there's no GradleModel to return a properly computed set of entries.
	 */
	public void setPersistedEntries(IClasspathEntry[] persistedEntries) {
		this.persistedEntries = persistedEntries;
		IProject eclipseProject = project.getProject();
		GradleSaveParticipant store = GradleSaveParticipant.getInstance();
		store.put(eclipseProject, id, encode(persistedEntries));
		store.save();
	}
	
	public boolean isInitialized() {
		try {
			project.getGradleModel(EclipseProject.class);
			return true;
		} catch (FastOperationFailedException e) {
		} catch (CoreException e) {
			GradleCore.log(e);
		}
		return false;
	}
	
	private IClasspathEntry[] decode(Serializable serializable) {
		JavaProject jp = (JavaProject) project.getJavaProject();
		if (serializable!=null) {
			String[] encoded = (String[]) serializable;
			IClasspathEntry[] decoded = new IClasspathEntry[encoded.length];
			for (int i = 0; i < decoded.length; i++) {
				decoded[i] = jp.decodeClasspathEntry(encoded[i]);
			}
			return decoded;
		}
		return null;
	}
	
	private Serializable encode(IClasspathEntry[] entries) {
		JavaProject jp = (JavaProject) project.getJavaProject();
		if (entries!=null) {
			String[] encoded = new String[entries.length];
			for (int i = 0; i < encoded.length; i++) {
				encoded[i] = jp.encodeClasspathEntry(entries[i]);
			}
			return encoded;
		}
		return null;
	}

	public String getDescription() {
		String desc = baseDescription;
		if (!isInitialized()) {
			if (getPersistedEntries()==null) {
				desc += " (uninitialized)";
			} else {
				desc += " (persisted)";
			}
		}
		return desc;
	}

	public void setJDTClassPathContainer() {
		if (project!=null) { 
			//project may be null, if project got deleted since the refresh got started... 
			try {
				JavaCore.setClasspathContainer(path,
						new IJavaProject[] { project.getJavaProject() },  
						new IClasspathContainer[] { null },
						new NullProgressMonitor());
				
				JavaCore.setClasspathContainer(path,
						new IJavaProject[] { project.getJavaProject() },  
						new IClasspathContainer[] { this },
						new NullProgressMonitor());
			} catch (JavaModelException e) {
				GradleCore.log(e);
			}
		}
	}
	
	public void clearClasspath() {
		project.getDependencyComputer().clearClasspath();
		oldModel = null; //Forces recomputation of entries on next 'getClasspathEntries
	}
	
	public synchronized IClasspathEntry[] getClasspathEntries() {
		try {
			EclipseProject gradleModel = project.getGradleModel();
			if (gradleModel!=null) {
				if (oldModel==gradleModel) {
					return getPersistedEntries();
				} else {
					IClasspathEntry[] entries = getFilteredClasspathEntries();
					setPersistedEntries(entries);
					oldModel = gradleModel;
					return entries;
				}
			}
		} catch (CoreException e) {
			GradleCore.log(e);
		} catch (FastOperationFailedException e) {
			// do nothing
		}
		
		//We reach here if we could not quickly get the container contents from Gradle we have one more thing to try
		IClasspathEntry[] persistedEntries = getPersistedEntries();
		if (persistedEntries!=null) {
			return persistedEntries;
		}
		
//		requestUpdate(false); // what good does this do?
		
		return new IClasspathEntry[] { };
	}

	protected abstract IClasspathEntry[] getFilteredClasspathEntries() throws FastOperationFailedException, CoreException;
}
