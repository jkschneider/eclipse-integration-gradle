package org.springsource.ide.eclipse.gradle.core.ivy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.HierarchicalEclipseProject;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.GradleModelProvider;
import org.springsource.ide.eclipse.gradle.core.GradleProject;
import org.springsource.ide.eclipse.gradle.core.classpathcontainer.FastOperationFailedException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class IvyUtils {
	@SuppressWarnings("serial")
	private static class IvyResourceNotFoundException extends Exception { };
	
	static LoadingCache<GradleProject, GradleModuleVersion> moduleVersionCache = CacheBuilder.newBuilder()
		.build(new CacheLoader<GradleProject, GradleModuleVersion>() {
            public GradleModuleVersion load(GradleProject project) throws CoreException, IvyResourceNotFoundException {
            	ProjectConnection gradleConnector = GradleModelProvider.getGradleConnector(project, null);
            	gradleConnector.newBuild().forTasks("generateDescriptorFileForIvyPublication").run();
            	
            	BufferedReader reader = null;
	            try {
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
							
							return new GradleModuleVersion() {
								@Override public String getVersion() { return null; }
								@Override public String getName() { return module; }
								@Override public String getGroup() { return org; }
							};
						}
					}
				}
	            catch (IOException e) { }
	            finally {
	            	if(reader != null) {
	            		try {
							reader.close();
						} catch (IOException e) {
						}
	            	}
	            }
            	
            	throw new IvyResourceNotFoundException();
            }
        });
	
	static LoadingCache<HierarchicalEclipseProject, ExternalDependency> ivyLibraryEquivalentCache = CacheBuilder.newBuilder()
		.build(new CacheLoader<HierarchicalEclipseProject, ExternalDependency>() {
            public ExternalDependency load(HierarchicalEclipseProject dep) throws GradleConnectionException, IllegalStateException, FastOperationFailedException, CoreException, IvyResourceNotFoundException {
            	for (GradleProject root : GradleCore.getGradleRootProjects()) {
    				for (HierarchicalEclipseProject subproject : root.getAllProjectsInBuild()) {
    					if(subproject.equals(dep)) {
    						ensureWorkspaceProjectResolverContentExists(root);
    						
    						ProjectConnection projectConnection = GradleModelProvider.getGradleConnector(new File(workspaceProjectResolverRoot(root) + "/" + subproject.getName() + "-resolver"));
    						if(projectConnection == null)
    							return null; // must not be an ivy project...
    						
    						EclipseProject model = projectConnection.getModel(EclipseProject.class);
    						GradleModuleVersion projectMV = gradleModuleVersion(GradleCore.create(subproject));
    						
    						// the fake gradle project will contain other deps (e.g. junit) in addition to the library representing the project
    						for (ExternalDependency modelDep : model.getClasspath()) {
    							GradleModuleVersion depMV = modelDep.getGradleModuleVersion();
    							if(projectMV.getGroup().equals(depMV.getGroup()) && projectMV.getName().equals(depMV.getName()))
    								return modelDep; 
    						}
    					}
    				}
    			}
            	throw new IvyResourceNotFoundException();
            }
		});
	
	public static GradleModuleVersion gradleModuleVersion(GradleProject project) {
		try {
			return moduleVersionCache.get(project);
		} catch (ExecutionException e) {
			// unable to open project connection for this project, return null
		}
		
		return null;
	}
	
	public static IProject getIvyProject(ExternalDependency dep) {
		for (GradleProject gradleProject : GradleCore.getGradleProjects())
			if(moduleVersionMatches(gradleModuleVersion(gradleProject), dep.getGradleModuleVersion()))
				return gradleProject.getProject();
		return null;
	}
	
	public static ExternalDependency getLibraryEquivalent(HierarchicalEclipseProject dep) {
		try {
			return ivyLibraryEquivalentCache.get(dep);
		} catch (ExecutionException e) {
		}
		return null;
	}
	
	private static boolean moduleVersionMatches(GradleModuleVersion v1, GradleModuleVersion v2) {
		return v1 != null && v2 != null && v1.getGroup().equals(v2.getGroup()) && v1.getName().equals(v2.getName());
	}
	
	private static String workspaceProjectResolverRoot(GradleProject project) throws IllegalStateException, FastOperationFailedException {
		return GradleCore.getInstance().getStateLocation().toFile().getPath() + "/" + project.getRootProject().getName();
	}
	
	// TODO we don't ever clean up the resolver content we put in the .metadata folder here
	private static void ensureWorkspaceProjectResolverContentExists(GradleProject project) {
		try {
			GradleProject rootProject = project.getRootProject();
			
			List<HierarchicalEclipseProject> allProjectsInBuild = rootProject.getAllProjectsInBuild();
			
			String uri = workspaceProjectResolverRoot(project);
			new File(uri).mkdirs();
			
			FileUtils.copyFile(new File(rootProject.getLocation().getPath() + "/build.gradle"), new File(uri + "/build.gradle"));
			
			List<String> settingsIncludes = new ArrayList<String>();
			
			for (HierarchicalEclipseProject hierarchicalEclipseProject : allProjectsInBuild) {
				GradleModuleVersion compileDep = gradleModuleVersion(GradleCore.create(hierarchicalEclipseProject));
				if(compileDep == null)
					continue;
				
				String projectName = hierarchicalEclipseProject.getName();
				new File(uri + "/" + projectName + "-resolver").mkdirs();
				
				FileWriter buildOut = new FileWriter(new File(uri + "/" + projectName + "-resolver/build.gradle"));
				buildOut.write("dependencies {\r\n");
				buildOut.write("   compile('" + compileDep.getGroup() + ":" + compileDep.getName() + ":+') { transitive = false }\r\n");
				buildOut.write("}");
				buildOut.close();
				
				settingsIncludes.add("\"" + projectName + "-resolver\"");
			}
			
			FileWriter settingsOut = new FileWriter(new File(uri + "/settings.gradle"));
			settingsOut.write("include " + StringUtils.join(settingsIncludes.iterator(), ", "));
			settingsOut.close();
		} catch (FastOperationFailedException e) {
		} catch (IOException e) {
		} catch (CoreException e) {
		}
	}
}
