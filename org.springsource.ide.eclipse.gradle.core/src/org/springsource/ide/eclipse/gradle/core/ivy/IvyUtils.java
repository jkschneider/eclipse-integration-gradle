package org.springsource.ide.eclipse.gradle.core.ivy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.HierarchicalEclipseProject;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleModelProvider;
import org.springsource.ide.eclipse.gradle.core.GradleProject;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.FastOperationFailedException;

public class IvyUtils {
	@SuppressWarnings("serial")
	static Map<GradleProject, GradleModuleVersion> moduleVersionCache = new HashMap<GradleProject, GradleModuleVersion>() {
		@Override
		public GradleModuleVersion get(Object projectObj) {
			GradleProject project = (GradleProject) projectObj;
			
			if(super.containsKey(project))
				return super.get(project);
			
			BufferedReader reader = null;
            try {
            	ProjectConnection gradleConnector = GradleModelProvider.getGradleConnector(project, new NullProgressMonitor());
            	gradleConnector.newBuild().forTasks("generateDescriptorFileForIvyPublication").run();
            	
				String projectPath = project.getLocation().getAbsolutePath();

				File file = new File(projectPath + "/build/publications/ivy/ivy.xml");
				reader = new BufferedReader(new FileReader(file));
				String line;
				while((line = reader.readLine()) != null) {
					if(line.contains("<info")) {
						Pattern pattern = Pattern.compile("organisation=\"([^\"]*)\"");
						Matcher matcher = pattern.matcher(line);
						matcher.find();
						final String org = matcher.group(1);
						
						pattern = Pattern.compile("module=\"([^\"]+)\"");
						matcher = pattern.matcher(line);
						matcher.find();
						final String module = matcher.group(1);
						
						GradleModuleVersion mv = new GradleModuleVersion() {
							@Override public String getVersion() { return null; }
							@Override public String getName() { return module; }
							@Override public String getGroup() { return org; }
						};
						
						super.put(project, mv);
						return mv;
					}
				}
			}
            catch (IOException e) {
            	GradleCore.log(e);
            } 
            catch (CoreException e) {
            	GradleCore.log(e);
			}
            finally {
            	if(reader != null) {
            		try {
						reader.close();
					} catch (IOException e) {
					}
            	}
            }
            
            return null;
        }
	};
	
	static class IvyLibraryEquivalency {
		Map<HierarchicalEclipseProject, Collection<? extends ExternalDependency>> ivyLibraryAndTransitivesByProject = new HashMap<HierarchicalEclipseProject, Collection<? extends ExternalDependency>>();
		Map<HierarchicalEclipseProject, ExternalDependency> ivyLibraryByProject = new HashMap<HierarchicalEclipseProject, ExternalDependency>();
		
        public Collection<? extends ExternalDependency> getLibraryAndTransitives(HierarchicalEclipseProject project) {
			if(!ivyLibraryAndTransitivesByProject.containsKey(project))
				computeLibrariesForCache(project, new NullProgressMonitor());
			return ivyLibraryAndTransitivesByProject.get(project);
        }
        
        public ExternalDependency getLibrary(HierarchicalEclipseProject project, IProgressMonitor monitor) {
			if(!ivyLibraryByProject.containsKey(project))
				computeLibrariesForCache(project, monitor);
			return ivyLibraryByProject.get(project);
        }
        
        private void computeLibrariesForCache(HierarchicalEclipseProject project, IProgressMonitor monitor) {
			try {
				ensureWorkspaceProjectResolverContentExists(GradleCore.create(project));
				
				ProjectConnection projectConnection = GradleModelProvider.getGradleConnector(
						new File(workspaceProjectResolverRoot(project) + "/workspace-resolver"),
						monitor);
				if(projectConnection == null) {
					ivyLibraryAndTransitivesByProject.put(project, new ArrayList<ExternalDependency>());
					return;
				}
				
				EclipseProject model = projectConnection.getModel(EclipseProject.class);
				ivyLibraryAndTransitivesByProject.put(project, model.getClasspath());
				
				// the fake gradle project will contain other deps (e.g. junit) in addition to the library representing the project
				GradleModuleVersion projectMV = gradleModuleVersion(GradleCore.create(project));
				for (ExternalDependency modelDep : model.getClasspath()) {
					GradleModuleVersion depMV = modelDep.getGradleModuleVersion();
					if(projectMV.getGroup().equals(depMV.getGroup()) && projectMV.getName().equals(depMV.getName())) {
						ivyLibraryByProject.put(project, modelDep);
					}
				}
			} catch (Throwable t) {
				GradleCore.log(t);
			}
        }
	};
	
	private static IvyLibraryEquivalency libraryEquivalency = new IvyLibraryEquivalency();
	
	public static GradleModuleVersion gradleModuleVersion(GradleProject project) {
		return moduleVersionCache.get(project);
	}
	
	public static IProject getIvyProject(ExternalDependency dep) {
		for (GradleProject gradleProject : GradleCore.getGradleProjects())
			if(moduleVersionMatches(gradleModuleVersion(gradleProject), dep.getGradleModuleVersion()))
				return gradleProject.getProject();
		return null;
	}
	
	public static Collection<? extends ExternalDependency> getLibraryAndTransitives(HierarchicalEclipseProject dep) {
		return libraryEquivalency.getLibraryAndTransitives(dep);
	}
	
	public static ExternalDependency getLibrary(HierarchicalEclipseProject dep, IProgressMonitor monitor) {
		return libraryEquivalency.getLibrary(dep, monitor);
	}
	
	private static boolean moduleVersionMatches(GradleModuleVersion v1, GradleModuleVersion v2) {
		return v1 != null && v2 != null && v1.getGroup().equals(v2.getGroup()) && v1.getName().equals(v2.getName());
	}
	
	private static String workspaceProjectResolverRoot(HierarchicalEclipseProject project) throws IllegalStateException, FastOperationFailedException {
		return GradleCore.getInstance().getStateLocation().toFile().getPath() + "/" + project.getName() + "-resolver";
	}
	
	// TODO we don't ever clean up the resolver content we put in the .metadata folder here
	private synchronized static void ensureWorkspaceProjectResolverContentExists(GradleProject project) {
		try {
			GradleProject rootProject = project.getRootProject();
			
			List<HierarchicalEclipseProject> allProjectsInBuild = rootProject.getAllProjectsInBuild();
			
			for (HierarchicalEclipseProject subproject : allProjectsInBuild) {
				String uri = workspaceProjectResolverRoot(subproject);
				FileUtils.deleteQuietly(new File(uri));
				new File(uri + "/workspace-resolver").mkdirs();
				
				try {
					FileUtils.copyFile(new File(subproject.getProjectDirectory().getPath() + "/gradle.properties"), new File(uri + "/workspace-resolver/gradle.properties"));
				} catch (IOException e) {
					// If source file is missing, don't copy 
				}
				
				try {
					if(rootProject.equals(project)) {
						copySingleProjectBuildGradle(rootProject, uri);
						return;
					}
					FileUtils.copyFile(new File(rootProject.getLocation().getPath() + "/build.gradle"), new File(uri + "/build.gradle"));
				} catch (IOException e) {
					// If build.gradle is missing, don't copy
				}
				
				if(rootProject.getSkeletalGradleModel().equals(subproject))
					continue;
				
				FileWriter buildOut = new FileWriter(new File(uri + "/workspace-resolver/build.gradle"));
				buildOut.write("dependencies {\r\n");
				
				GradleModuleVersion compileDep = gradleModuleVersion(GradleCore.create(subproject));
				if(compileDep != null)
					buildOut.write("   compile '" + compileDep.getGroup() + ":" + compileDep.getName() + ":+'\r\n");
				
				buildOut.write("}");
				buildOut.close();
				
				FileWriter settingsOut = new FileWriter(new File(uri + "/settings.gradle"));
				settingsOut.write("include \"workspace-resolver\"");
				settingsOut.close();
			}
		} catch (FastOperationFailedException e) {
			GradleCore.log(e);
		} catch (IOException e) {
			GradleCore.log(e);
		} catch (CoreException e) {
			GradleCore.log(e);
		}
	}

	/**
	 * Copy single project build.gradle script, replacing dependencies { .. } with a single dependency representing the project
	 * @param rootProject
	 * @param uri
	 * @throws IOException
	 */
	private static void copySingleProjectBuildGradle(GradleProject rootProject, String uri) throws IOException {
		String buildGradleScript = FileUtils.readFileToString(new File(rootProject.getLocation().getPath() + "/build.gradle"));
		// TODO JON replace dependencies with this single project
		
		FileUtils.writeStringToFile(new File(uri + "/workspace-resolver/build.gradle"), buildGradleScript);
	}
}
