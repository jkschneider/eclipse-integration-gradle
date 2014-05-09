package org.springsource.ide.eclipse.gradle.core.ivy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
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
            	ProjectConnection gradleConnector = GradleModelProvider.getGradleConnector(project, null);
            	gradleConnector.newBuild().forTasks("generateDescriptorFileForIvyPublication").run();
            	
				String projectPath = project.getProject().getLocation().toOSString();

				reader = new BufferedReader(new FileReader(new File(projectPath + "/build/publications/ivy/ivy.xml")));
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
	
	@SuppressWarnings("serial")
	static Map<HierarchicalEclipseProject, ExternalDependency> ivyLibraryEquivalentCache = new HashMap<HierarchicalEclipseProject, ExternalDependency>() {
		@Override
        public ExternalDependency get(Object depObject) {
			HierarchicalEclipseProject dep = (HierarchicalEclipseProject) depObject;
			
			if(super.containsKey(dep))
				return super.get(dep);
			
        	for (GradleProject root : GradleCore.getGradleRootProjects()) {
				try {
					for (HierarchicalEclipseProject subproject : root.getAllProjectsInBuild()) {
						if(subproject.equals(dep)) {
							ensureWorkspaceProjectResolverContentExists(root);
							
							ProjectConnection projectConnection = GradleModelProvider.getGradleConnector(new File(workspaceProjectResolverRoot(root) + "/workspace-resolver"));
							if(projectConnection == null) {
								super.put(dep, null);
								return null; // must not be an ivy project...
							}
							
							EclipseProject model = projectConnection.getModel(EclipseProject.class);
							GradleModuleVersion projectMV = gradleModuleVersion(GradleCore.create(subproject));
							
							// the fake gradle project will contain other deps (e.g. junit) in addition to the library representing the project
							for (ExternalDependency modelDep : model.getClasspath()) {
								GradleModuleVersion depMV = modelDep.getGradleModuleVersion();
								if(projectMV.getGroup().equals(depMV.getGroup()) && projectMV.getName().equals(depMV.getName())) {
									super.put(dep, modelDep);
									return modelDep; 
								}
							}
						}
					}
				} catch (Throwable t) {
					GradleCore.log(t);
				}
			}
        	return null;
        }
	};
	
	public static GradleModuleVersion gradleModuleVersion(GradleProject project) {
		return moduleVersionCache.get(project);
	}
	
	public static IProject getIvyProject(ExternalDependency dep) {
		for (GradleProject gradleProject : GradleCore.getGradleProjects())
			if(moduleVersionMatches(gradleModuleVersion(gradleProject), dep.getGradleModuleVersion()))
				return gradleProject.getProject();
		return null;
	}
	
	public static ExternalDependency getLibraryEquivalent(HierarchicalEclipseProject dep) {
		return ivyLibraryEquivalentCache.get(dep);
	}
	
	private static boolean moduleVersionMatches(GradleModuleVersion v1, GradleModuleVersion v2) {
		return v1 != null && v2 != null && v1.getGroup().equals(v2.getGroup()) && v1.getName().equals(v2.getName());
	}
	
	private static String workspaceProjectResolverRoot(GradleProject project) throws IllegalStateException, FastOperationFailedException {
		return GradleCore.getInstance().getStateLocation().toFile().getPath() + "/" + project.getName() + "-resolver";
	}
	
	// TODO we don't ever clean up the resolver content we put in the .metadata folder here
	private static void ensureWorkspaceProjectResolverContentExists(GradleProject project) {
		try {
			GradleProject rootProject = project.getRootProject();
			
			List<HierarchicalEclipseProject> allProjectsInBuild = rootProject.getAllProjectsInBuild();
			
			String uri = workspaceProjectResolverRoot(project);
			FileUtils.deleteQuietly(new File(uri));
			new File(uri).mkdirs();
			
			for (HierarchicalEclipseProject hierarchicalEclipseProject : allProjectsInBuild) {
				try {
					FileUtils.copyFile(new File(rootProject.getLocation().getPath() + "/build.gradle"), new File(uri + "/build.gradle"));
				} catch (IOException e) {
					// If build.gradle is missing, don't copy
				}
				
				if(rootProject.getSkeletalGradleModel().equals(hierarchicalEclipseProject))
					continue;
				GradleModuleVersion compileDep = gradleModuleVersion(GradleCore.create(hierarchicalEclipseProject));
				if(compileDep == null)
					continue;
				
				new File(uri + "/workspace-resolver").mkdirs();
				
				FileWriter buildOut = new FileWriter(new File(uri + "/workspace-resolver/build.gradle"));
				buildOut.write("dependencies {\r\n");
				buildOut.write("   compile('" + compileDep.getGroup() + ":" + compileDep.getName() + ":+') { transitive = false }\r\n");
				buildOut.write("}");
				buildOut.close();
				
				try {
					FileUtils.copyFile(new File(hierarchicalEclipseProject.getProjectDirectory().getPath() + "/gradle.properties"), new File(uri + "/workspace-resolver/gradle.properties"));
				} catch (IOException e) {
					// If source file is missing, don't copy 
				}
			}
			
			FileWriter settingsOut = new FileWriter(new File(uri + "/settings.gradle"));
			settingsOut.write("include \"workspace-resolver\"");
			settingsOut.close();
		} catch (FastOperationFailedException e) {
		} catch (IOException e) {
		} catch (CoreException e) {
		}
	}
}
