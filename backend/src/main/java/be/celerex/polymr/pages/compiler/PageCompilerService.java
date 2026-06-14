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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

@ApplicationScoped
public class PageCompilerService {
	private static final String COMPILER_BUNDLE_RESOURCE = "/sfc/compiler-sfc.bundle.js";
	private static final String PAGE_COMPILER_RESOURCE = "/sfc/page-compile.js";
	@Inject
	ObjectMapper objectMapper;

	private String compilerBundle;
	private String pageCompilerScript;

	@PostConstruct
	void init() {
		compilerBundle = readResource(COMPILER_BUNDLE_RESOURCE);
		pageCompilerScript = readResource(PAGE_COMPILER_RESOURCE);
	}

	public PageCompilationResult compile(PageCompilationRequest request) {
		PageCompilationRequest safeRequest = request == null
			? new PageCompilationRequest("", List.of(), java.util.Map.of(), "page", java.util.Map.of())
			: new PageCompilationRequest(
				request.source(),
				request.allowlist() == null ? List.of() : request.allowlist(),
				request.externalFrontendImports() == null ? java.util.Map.of() : request.externalFrontendImports(),
				request.pageId() == null || request.pageId().isBlank() ? "page" : request.pageId(),
				request.availablePageModules() == null ? java.util.Map.of() : request.availablePageModules()
			);
		try (Context context = Context.newBuilder("js") .allowHostAccess(HostAccess.NONE) .allowHostClassLookup((name) -> false) .allowIO(false) .option("js.ecmascript-version", "latest") .build()) {
			String polyfills = "var globalThis = this; var window = this; var process = { env: { NODE_ENV: 'production' } };";
			context.eval("js", polyfills);
			context.eval(Source.newBuilder("js", compilerBundle, "compiler-sfc.bundle.js").buildLiteral());
			context.eval(Source.newBuilder("js", pageCompilerScript, "page-compile.js").buildLiteral());
			Value compiler = context.getBindings("js").getMember("__PolymrPageCompiler");
			if (compiler == null || !compiler.hasMember("compilePageSource")) {
				throw new PageCompilationException("Page compiler did not initialize correctly.");
			}
			Value result = compiler.getMember("compilePageSourceFromJson").execute(toCompilerInputJson(safeRequest));
			return new PageCompilationResult(
				stringMember(result, "compiledBundle"),
				stringMember(result, "compileErrors"),
				stringMember(result, "compileErrorDetail")
			);
		}
		catch (PolyglotException exception) {
			throw new PageCompilationException(buildErrorMessage(exception), exception);
		}
		catch (IOException exception) {
			throw new PageCompilationException("Unable to prepare page compiler input.", exception);
		}
	}

	private String toCompilerInputJson(PageCompilationRequest request) throws IOException {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("source", request.source() == null ? "" : request.source());
		node.put("pageId", request.pageId());
		node.putPOJO("allowlist", request.allowlist());
		node.putPOJO("externalFrontendImports", request.externalFrontendImports());
		node.putPOJO("availablePageModules", request.availablePageModules());
		return objectMapper.writeValueAsString(node);
	}

	private String stringMember(Value value, String member) {
		if (value == null || !value.hasMember(member)) {
			return "";
		}
		Value memberValue = value.getMember(member);
		if (memberValue == null || memberValue.isNull()) {
			return "";
		}
		return memberValue.asString();
	}

	private String buildErrorMessage(PolyglotException exception) {
		if (exception == null) {
			return "Page compilation failed.";
		}
		if (exception.isGuestException()
				&& exception.getMessage() != null
				&& !exception.getMessage()
					.isBlank()) {
			return exception.getMessage();
		}
		return "Page compilation failed.";
	}

	private String readResource(String path) {
		try (InputStream inputStream = PageCompilerService.class.getResourceAsStream(path)) {
			if (inputStream == null) {
				throw new PageCompilationException("Missing page compiler resource: " + path);
			}
			return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new PageCompilationException("Unable to read page compiler resource: " + path, exception);
		}
	}
}
