/*
* Copyright (C) 2026 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.celerex.polymr.pages.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class PageCompilerServiceTest {
	@Inject
	PageCompilerService pageCompilerService;

	@Test
	public void compilesValidSfc() {
		PageCompilationResult result = pageCompilerService.compile(
			new PageCompilationRequest(
				"<template><div class=\"test\">{{ msg }}</div></template>\n<script setup>\nconst msg = 'hello'\n</script>\n<style scoped>.test { color: red; }</style>",
				List.of(),
				Map.of(),
				"page-1",
				java.util.Map.of()
			)
		);
		assertTrue(result.compileErrors() == null || result.compileErrors().isBlank());
		assertTrue(result.compiledBundle().contains("render"));
		assertTrue(result.compiledBundle().contains("data-v-page-page-1"));
	}

	@Test
	public void rejectsImportsOutsideAllowlist() {
		PageCompilationResult result = pageCompilerService.compile(
			new PageCompilationRequest(
				"<script setup>\nimport foo from 'not-allowed'\n</script>",
				List.of(),
				Map.of(),
				"page-2",
				java.util.Map.of()
			)
		);
		assertTrue(result.compiledBundle() == null || result.compiledBundle().isBlank());
		assertTrue(result.compileErrors().contains("Import not allowed"));
	}

	@Test
	public void reportsParseErrors() {
		PageCompilationResult result = pageCompilerService.compile(
			new PageCompilationRequest("<template><div></template>", List.of(), Map.of(), "page-3", java.util.Map.of())
		);
		assertFalse(result.compileErrors() == null || result.compileErrors().isBlank());
		assertTrue(result.compiledBundle() == null || result.compiledBundle().isBlank());
	}

	@Test
	public void compilesTypescriptScriptSetup() {
		PageCompilationResult result = pageCompilerService.compile(
			new PageCompilationRequest(
				"<template><div>{{ message.toUpperCase() }}</div></template>\n<script setup lang=\"ts\">\nconst message: string = 'hello'\n</script>",
				List.of(),
				Map.of(),
				"page-4",
				Map.of()
			)
		);
		assertTrue(result.compileErrors() == null || result.compileErrors().isBlank());
		assertTrue(result.compiledBundle().contains("message"));
		assertTrue(result.compiledBundle().contains("render"));
	}

	@Test
	public void resolvesPageImportsByPath() {
		PageCompilationResult result = pageCompilerService.compile(
			new PageCompilationRequest(
				"<script setup>\nimport MyComponent from '@polymr/pages/components/MyComponent.vue'\nconst component = MyComponent\n</script>",
				List.of(),
				Map.of(),
				"page-5",
				Map.of("components/MyComponent.vue", "available")
			)
		);
		assertTrue(result.compileErrors() == null || result.compileErrors().isBlank());
		assertTrue(result.compiledBundle().contains("PolymrPages[\"components/MyComponent.vue\"]"));
	}

	@Test
	public void rejectsUnknownPageImportsByPath() {
		PageCompilationResult result = pageCompilerService.compile(
			new PageCompilationRequest(
				"<script setup>\nimport MyComponent from '@polymr/pages/components/MyComponent.vue'\n</script>",
				List.of(),
				Map.of(),
				"page-6",
				Map.of()
			)
		);
		assertTrue(result.compiledBundle() == null || result.compiledBundle().isBlank());
		assertTrue(result.compileErrors().contains("Page import not available"));
	}

	@Test
	public void supportsAllFrontendApiSubpathImports() {
		PageCompilationResult result = pageCompilerService.compile(
			new PageCompilationRequest(
				"<script setup>\n" + "import getUsers from '@polymr/api/getUsers'\n"
					+ "import callTool from '@polymr/api/callTool'\n"
					+ "import callScript from '@polymr/api/callScript'\n"
					+ "import createAttachmentUrl from '@polymr/api/createAttachmentUrl'\n"
					+ "import navigateTo from '@polymr/api/navigateTo'\n" + "import notify from '@polymr/api/notify'\n"
					+ "import uploadAttachment from '@polymr/api/uploadAttachment'\n"
					+ "const apiFns = [getUsers, callTool, callScript, createAttachmentUrl, navigateTo, notify, uploadAttachment]\n"
					+ "</script>",
				List.of(),
				Map.of(),
				"page-7",
				Map.of()
			)
		);
		assertTrue(result.compileErrors() == null || result.compileErrors().isBlank());
		assertTrue(result.compiledBundle().contains("PolymrApi.getUsers"));
		assertTrue(result.compiledBundle().contains("PolymrApi.callTool"));
		assertTrue(result.compiledBundle().contains("PolymrApi.callScript"));
		assertTrue(result.compiledBundle().contains("PolymrApi.createAttachmentUrl"));
		assertTrue(result.compiledBundle().contains("PolymrApi.navigateTo"));
		assertTrue(result.compiledBundle().contains("PolymrApi.notify"));
		assertTrue(result.compiledBundle().contains("PolymrApi.uploadAttachment"));
	}

	@Test
	public void resolvesWorkspaceExternalFrontendImports() {
		PageCompilationResult result = pageCompilerService.compile(
			new PageCompilationRequest(
				"<script setup>\nimport * as echarts from 'echarts'\nconst charting = echarts\n</script>",
				List.of("echarts"),
				Map.of("echarts", "echarts"),
				"page-8",
				Map.of()
			)
		);
		assertTrue(result.compileErrors() == null || result.compileErrors().isBlank());
		assertTrue(result.compiledBundle().contains("PolymrExternalImports[\"echarts\"]"));
	}
}
