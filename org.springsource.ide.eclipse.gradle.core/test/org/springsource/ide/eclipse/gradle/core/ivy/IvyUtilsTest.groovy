package org.springsource.ide.eclipse.gradle.core.ivy;
	
import org.gradle.tooling.model.GradleModuleVersion;
import org.junit.Test;

public class IvyUtilsTest {
	@Test
	void injectDependenciesIntoExistingBuildScript() {
		GradleModuleVersion mv = new GradleModuleVersion() {
			@Override public String getGroup() { return "org"; }
			@Override public String getName() { return "mod"; }
			@Override public String getVersion() { return "1"; }
		};
		
		assert !IvyUtils.injectDependenciesIntoExistingBuildScript(mv, "dependencies }").isEmpty(); // malformatted build script, but at least we don't blow up!
		assert !IvyUtils.injectDependenciesIntoExistingBuildScript(mv, "dependencies {").isEmpty(); // malformatted build script, but at least we don't blow up!
		assert "dependencies { compile 'org:mod:+' }" == IvyUtils.injectDependenciesIntoExistingBuildScript(mv, "dependencies {\r\n{\r\n} }");
		assert "dependencies { compile 'org:mod:+' }" == IvyUtils.injectDependenciesIntoExistingBuildScript(mv, "dependencies { { } }");
		assert "dependencies { compile 'org:mod:+' }" == IvyUtils.injectDependenciesIntoExistingBuildScript(mv, "dependencies { }");
	}
}
