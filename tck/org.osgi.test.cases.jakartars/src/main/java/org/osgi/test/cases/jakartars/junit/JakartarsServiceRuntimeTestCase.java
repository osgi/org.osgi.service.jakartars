/*******************************************************************************
 * Copyright (c) Contributors to the Eclipse Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0 
 *******************************************************************************/

package org.osgi.test.cases.jakartars.junit;

import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.lang.Boolean.TRUE;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.osgi.framework.Constants.SERVICE_ID;
import static org.osgi.service.jakartars.runtime.dto.DTOConstants.FAILURE_REASON_DUPLICATE_NAME;
import static org.osgi.service.jakartars.runtime.dto.DTOConstants.FAILURE_REASON_NOT_AN_EXTENSION_TYPE;
import static org.osgi.service.jakartars.runtime.dto.DTOConstants.FAILURE_REASON_REQUIRED_APPLICATION_UNAVAILABLE;
import static org.osgi.service.jakartars.runtime.dto.DTOConstants.FAILURE_REASON_REQUIRED_EXTENSIONS_UNAVAILABLE;
import static org.osgi.service.jakartars.runtime.dto.DTOConstants.FAILURE_REASON_SERVICE_NOT_GETTABLE;
import static org.osgi.service.jakartars.runtime.dto.DTOConstants.FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE;
import static org.osgi.service.jakartars.runtime.dto.DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
import static org.osgi.service.jakartars.whiteboard.JakartarsWhiteboardConstants.JAKARTA_RS_APPLICATION_BASE;
import static org.osgi.service.jakartars.whiteboard.JakartarsWhiteboardConstants.JAKARTA_RS_APPLICATION_SELECT;
import static org.osgi.service.jakartars.whiteboard.JakartarsWhiteboardConstants.JAKARTA_RS_EXTENSION;
import static org.osgi.service.jakartars.whiteboard.JakartarsWhiteboardConstants.JAKARTA_RS_EXTENSION_SELECT;
import static org.osgi.service.jakartars.whiteboard.JakartarsWhiteboardConstants.JAKARTA_RS_NAME;
import static org.osgi.service.jakartars.whiteboard.JakartarsWhiteboardConstants.JAKARTA_RS_RESOURCE;

import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jakartars.runtime.JakartarsServiceRuntime;
import org.osgi.service.jakartars.runtime.dto.ApplicationDTO;
import org.osgi.service.jakartars.runtime.dto.ExtensionDTO;
import org.osgi.service.jakartars.runtime.dto.FailedApplicationDTO;
import org.osgi.service.jakartars.runtime.dto.FailedExtensionDTO;
import org.osgi.service.jakartars.runtime.dto.FailedResourceDTO;
import org.osgi.service.jakartars.runtime.dto.ResourceDTO;
import org.osgi.service.jakartars.runtime.dto.ResourceMethodInfoDTO;
import org.osgi.service.jakartars.whiteboard.JakartarsWhiteboardConstants;
import org.osgi.test.cases.jakartars.applications.SimpleApplication;
import org.osgi.test.cases.jakartars.extensions.BoundStringReplacer.NameBound;
import org.osgi.test.cases.jakartars.extensions.BoundStringReplacer;
import org.osgi.test.cases.jakartars.extensions.OSGiTextMimeTypeCodec;
import org.osgi.test.cases.jakartars.extensions.StringReplacer;
import org.osgi.test.cases.jakartars.resources.EchoResource;
import org.osgi.test.cases.jakartars.resources.NameBoundWhiteboardResource;
import org.osgi.test.cases.jakartars.resources.WhiteboardResource;
import org.osgi.util.promise.Promise;

import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.WriterInterceptor;

/**
 * This test covers the lifecycle behaviours described in section 151.4
 */
public class JakartarsServiceRuntimeTestCase extends AbstractJakartarsTestCase {

	private JakartarsServiceRuntime runtimeService;

	@BeforeEach
	public void getRuntimeService() {
		runtimeService = context.getService(runtime);
	}

	/**
	 * Section 151.2.1 Register a simple JAX-RS singleton resource and show that
	 * it appears in the DTOs
	 * 
	 * @throws Exception
	 */
	@Test
	public void testWhiteboardResourceDTO() throws Exception {

		ResourceDTO[] resourceDTOs = runtimeService
				.getRuntimeDTO().defaultApplication.resourceDTOs;
		int previousResourceLength = resourceDTOs == null ? 0
				: resourceDTOs.length;

		// Register a whiteboard resource

		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_RESOURCE, Boolean.TRUE);
		properties.put(JAKARTA_RS_NAME, "test");

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<WhiteboardResource> reg = context
				.registerService(WhiteboardResource.class,
						new WhiteboardResource(), properties);

		awaitSelection.getValue();

		resourceDTOs = runtimeService
				.getRuntimeDTO().defaultApplication.resourceDTOs;

		assertThat(resourceDTOs).hasSize(previousResourceLength + 1);

		ResourceDTO whiteboardResource = findResourceDTO(resourceDTOs, "test");

		assertThat(whiteboardResource).isNotNull();
		assertThat(whiteboardResource.serviceId).isEqualTo(reg.getReference().getProperty(SERVICE_ID));
		assertThat(whiteboardResource.resourceMethods).hasSize(5);

		for (ResourceMethodInfoDTO infoDTO : whiteboardResource.resourceMethods) {
			checkWhiteboardResourceMethod(infoDTO);
		}
	}

	private void checkWhiteboardResourceMethod(ResourceMethodInfoDTO infoDTO) {
		// The spec is a little ambiguous about whether the @Path annotation's
		// value should be reproduced verbatim or as it would be handled by the
		// container which adds a leading "/" if it's not present. We therefore
		// remove the leading / if there is one to be lenient
		String path = infoDTO.path.startsWith("/") ? infoDTO.path.substring(1)
				: infoDTO.path;

		switch (infoDTO.method) {
			case "DELETE" :
				assertThat(path).isEqualTo("whiteboard/resource/{name}");
				assertThat(infoDTO.consumingMimeType).isNull();
				assertThat(infoDTO.producingMimeType).isNotNull();
				assertThat(infoDTO.nameBindings).isNull();
				assertThat(infoDTO.producingMimeType[0]).isEqualTo(TEXT_PLAIN);
				break;
			case "PUT" :
				assertThat(path).isEqualTo("whiteboard/resource/{name}");
				assertThat(infoDTO.consumingMimeType).isNull();
				assertThat(infoDTO.producingMimeType).isNotNull();
				assertThat(infoDTO.nameBindings).isNull();
				assertThat(infoDTO.producingMimeType[0]).isEqualTo(TEXT_PLAIN);
				break;
			case "POST" :
				assertThat(path).isEqualTo("whiteboard/resource/{oldName}/{newName}");
				assertThat(infoDTO.consumingMimeType).isNull();
				assertThat(infoDTO.producingMimeType).isNotNull();
				assertThat(infoDTO.nameBindings).isNull();
				assertThat(infoDTO.producingMimeType[0]).isEqualTo(TEXT_PLAIN);
				break;
			case "GET" :
				if ("whiteboard/resource/{name}".equals(path)
						|| "whiteboard/resource".equals(path)) {
					assertThat(infoDTO.consumingMimeType).isNull();
					assertThat(infoDTO.producingMimeType).isNotNull();
					assertThat(infoDTO.nameBindings).isNull();
					assertThat(infoDTO.producingMimeType[0]).isEqualTo(TEXT_PLAIN);
				} else {
					fail("Invalid resource path " + path);
				}
				break;
			default :
				fail("Unexpected Method " + infoDTO);
		}
	}

	private ResourceDTO findResourceDTO(ResourceDTO[] resourceDTOs,
			String name) {
		ResourceDTO whiteboardResource = null;
		for (ResourceDTO resource : resourceDTOs) {
			if (name.equals(resource.name)) {
				whiteboardResource = resource;
				break;
			}
		}
		return whiteboardResource;
	}

	/**
	 * Section 151.2.1 Register a simple JAX-RS extension and show that it
	 * appears in the DTOs
	 * 
	 * @throws Exception
	 */
	@Test
	public void testWhiteboardExtensionDTO() throws Exception {

		ExtensionDTO[] extensionDTOs = runtimeService
				.getRuntimeDTO().defaultApplication.extensionDTOs;
		int previousExtensionLength = extensionDTOs == null ? 0
				: extensionDTOs.length;

		// Register a whiteboard extension

		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_EXTENSION, Boolean.TRUE);
		properties.put(JAKARTA_RS_NAME, "test");

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration< ? > reg = context
				.registerService(new String[] {
						MessageBodyReader.class.getName(),
						MessageBodyWriter.class.getName()
		}, new OSGiTextMimeTypeCodec(), properties);

		awaitSelection.getValue();

		extensionDTOs = runtimeService
				.getRuntimeDTO().defaultApplication.extensionDTOs;

		assertThat(extensionDTOs).hasSize(previousExtensionLength + 1);

		ExtensionDTO whiteboardExtension = null;
		for (ExtensionDTO extension : extensionDTOs) {
			if ("test".equals(extension.name)) {
				whiteboardExtension = extension;
				break;
			}
		}

		assertThat(whiteboardExtension).isNotNull();
		assertThat(whiteboardExtension.serviceId).isEqualTo(reg.getReference().getProperty(SERVICE_ID));

		assertThat(new HashSet<>(asList(whiteboardExtension.extensionTypes))).isEqualTo(new HashSet<>(asList(MessageBodyReader.class.getName(),
						MessageBodyWriter.class.getName())));

		assertThat(whiteboardExtension.consumes).isNotNull();
		assertThat(whiteboardExtension.consumes.length).isEqualTo(1);
		assertThat(whiteboardExtension.consumes[0]).isEqualTo("osgi/text");
		assertThat(whiteboardExtension.produces).isNotNull();
		assertThat(whiteboardExtension.produces.length).isEqualTo(1);
		assertThat(whiteboardExtension.produces[0]).isEqualTo("osgi/text");

		assertThat(whiteboardExtension.nameBindings).isNull();
		assertThat(whiteboardExtension.filteredByName).isNull();
	}

	/**
	 * Section 151.2.1 Show that name binding is reflected in the DTOs
	 * 
	 * @throws Exception
	 */
	@Test
	public void testNameBoundDTOs() throws Exception {

		// Register a whiteboard resource
		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JakartarsWhiteboardConstants.JAKARTA_RS_RESOURCE,
				Boolean.TRUE);

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<NameBoundWhiteboardResource> resourceReg = context
				.registerService(NameBoundWhiteboardResource.class,
						new NameBoundWhiteboardResource(), properties);

		awaitSelection.getValue();

		// Register a whiteboard extension
		awaitSelection = helper.awaitModification(runtime, 5000);

		properties = new Hashtable<>();
		properties.put(JAKARTA_RS_EXTENSION, TRUE);
		properties.put(JAKARTA_RS_NAME, "test");
		ServiceRegistration<WriterInterceptor> extensionReg = context
				.registerService(WriterInterceptor.class,
						new BoundStringReplacer("fizz", "fizzbuzz"),
						properties);
		awaitSelection.getValue();

		ExtensionDTO[] extensionDTOs = runtimeService
				.getRuntimeDTO().defaultApplication.extensionDTOs;

		ExtensionDTO whiteboardExtension = null;
		for (ExtensionDTO extension : extensionDTOs) {
			if ("test".equals(extension.name)) {
				whiteboardExtension = extension;
				break;
				}
		}

		assertThat(whiteboardExtension).isNotNull();
		assertThat(whiteboardExtension.serviceId).isEqualTo(extensionReg.getReference().getProperty(SERVICE_ID));

		assertThat(asList(whiteboardExtension.nameBindings)).isEqualTo(asList(NameBound.class.getName()));

		assertThat(whiteboardExtension.filteredByName).isNotNull();
		assertThat(whiteboardExtension.filteredByName.length).isEqualTo(1);

		checkBoundResourceDTO(resourceReg,
				whiteboardExtension.filteredByName[0]);

		checkBoundResourceDTO(resourceReg, findResourceDTO(
				runtimeService.getRuntimeDTO().defaultApplication.resourceDTOs,
				whiteboardExtension.filteredByName[0].name));

	}

	private void checkBoundResourceDTO(
			ServiceRegistration<NameBoundWhiteboardResource> resourceReg,
			ResourceDTO resourceDTO) {
		assertThat(resourceDTO.serviceId).isEqualTo(resourceReg.getReference().getProperty(SERVICE_ID));

		assertThat(resourceDTO.resourceMethods).isNotNull();
		assertThat(resourceDTO.resourceMethods).hasSize(2);

		for (ResourceMethodInfoDTO dto : resourceDTO.resourceMethods) {
			switch (dto.path) {
				case "/whiteboard/name/bound" :
				case "whiteboard/name/bound" :
					assertThat(asList(dto.nameBindings)).isEqualTo(asList(NameBound.class.getName()));
					break;
				case "/whiteboard/name/unbound" :
				case "whiteboard/name/unbound" :
					assertThat(dto.nameBindings).isNull();
					break;
				default :
					fail("Unexpected method " + dto);
			}
		}
	}

	/**
	 * Section 151.2.1 Register a JAX-RS application and show that it appears in
	 * the DTOs
	 * 
	 * @throws Exception
	 */
	@Test
	public void testWhiteboardApplicationDTO() throws Exception {

		ApplicationDTO[] applicationDTOs = runtimeService
				.getRuntimeDTO().applicationDTOs;
		int previousApplicationsLength = applicationDTOs == null ? 0
				: applicationDTOs.length;

		// Register a whiteboard application

		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_APPLICATION_BASE, "/test");
		properties.put(JAKARTA_RS_NAME, "test");

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<Application> reg = context
				.registerService(Application.class,
						new SimpleApplication(emptySet(),
								singleton(new WhiteboardResource())),
						properties);

		awaitSelection.getValue();

		applicationDTOs = runtimeService.getRuntimeDTO().applicationDTOs;

		ApplicationDTO whiteboardApp = null;
		for (ApplicationDTO app : applicationDTOs) {
			if ("test".equals(app.name)) {
				whiteboardApp = app;
				break;
			}
		}

		assertThat(applicationDTOs).hasSize(previousApplicationsLength + 1);

		assertThat(whiteboardApp).isNotNull();
		assertThat(whiteboardApp.serviceId).isEqualTo(reg.getReference().getProperty(SERVICE_ID));
		assertThat(whiteboardApp.resourceMethods).hasSize(5);

		for (ResourceMethodInfoDTO infoDTO : whiteboardApp.resourceMethods) {
			checkWhiteboardResourceMethod(infoDTO);
		}
	}

	/**
	 * Section 151.3 Clashing names must trigger a failure
	 * 
	 * @throws Exception
	 */
	@Test
	public void testResourcesWithClashingNames() throws Exception {

		// Register a whiteboard resource

		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_RESOURCE, Boolean.TRUE);
		properties.put(JAKARTA_RS_NAME, "test");

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		context.registerService(EchoResource.class, new EchoResource(),
				properties);

		awaitSelection.getValue();

		// Register a second clashing whiteboard resource
		awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<EchoResource> reg2 = context.registerService(
				EchoResource.class, new EchoResource(), properties);

		awaitSelection.getValue();

		// Register a whiteboard extension, also with a clashing name

		awaitSelection = helper.awaitModification(runtime, 5000);

		properties = new Hashtable<>();
		properties.put(JAKARTA_RS_EXTENSION, Boolean.TRUE);
		properties.put(JAKARTA_RS_NAME, "test");

		ServiceRegistration<WriterInterceptor> extensionReg = context
				.registerService(WriterInterceptor.class,
						new StringReplacer("fizz", "fizzbuzz"), properties);

		awaitSelection.getValue();

		FailedResourceDTO[] dtos = runtimeService
				.getRuntimeDTO().failedResourceDTOs;
		assertThat(dtos).isNotNull();
		assertThat(dtos).hasSize(1);
		assertThat(dtos[0].failureReason).isEqualTo(FAILURE_REASON_DUPLICATE_NAME);
		assertThat(dtos[0].serviceId).isEqualTo(reg2.getReference().getProperty(SERVICE_ID));

		FailedExtensionDTO[] exDtos = runtimeService
				.getRuntimeDTO().failedExtensionDTOs;
		assertThat(exDtos).isNotNull();
		assertThat(exDtos).hasSize(1);
		assertThat(exDtos[0].failureReason).isEqualTo(FAILURE_REASON_DUPLICATE_NAME);
		assertThat(exDtos[0].serviceId).isEqualTo(extensionReg.getReference().getProperty(SERVICE_ID));

	}

	/**
	 * Section 151.3 Missing extensions
	 * 
	 * @throws Exception
	 */
	@Test
	public void testMissingExtension() throws Exception {
		// Register a whiteboard resource with an extension requirement
		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_RESOURCE, Boolean.TRUE);
		properties.put(JAKARTA_RS_EXTENSION_SELECT, "(foo=bar)");

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<EchoResource> reg = context.registerService(
				EchoResource.class, new EchoResource(), properties);

		awaitSelection.getValue();

		FailedResourceDTO[] dtos = runtimeService
				.getRuntimeDTO().failedResourceDTOs;
		assertThat(dtos).isNotNull();
		assertThat(dtos).hasSize(1);
		assertThat(dtos[0].failureReason).isEqualTo(FAILURE_REASON_REQUIRED_EXTENSIONS_UNAVAILABLE);
		assertThat(dtos[0].serviceId).isEqualTo(reg.getReference().getProperty(SERVICE_ID));

	}

	/**
	 * Section 151.3 Missing applications
	 * 
	 * @throws Exception
	 */
	@Test
	public void testMissingApplications() throws Exception {
		// Register a whiteboard resource with an application selection
		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_RESOURCE, Boolean.TRUE);
		properties.put(JAKARTA_RS_APPLICATION_SELECT, "(foo=bar)");

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<EchoResource> reg = context.registerService(
				EchoResource.class, new EchoResource(), properties);

		awaitSelection.getValue();

		FailedResourceDTO[] dtos = runtimeService
				.getRuntimeDTO().failedResourceDTOs;
		assertThat(dtos).isNotNull();
		assertThat(dtos).hasSize(1);
		assertThat(dtos[0].failureReason).isEqualTo(FAILURE_REASON_REQUIRED_APPLICATION_UNAVAILABLE);
		assertThat(dtos[0].serviceId).isEqualTo(reg.getReference().getProperty(SERVICE_ID));

	}

	/**
	 * 151.2.2.2 bad filter
	 * 
	 * @throws Exception
	 */
	@Test
	public void testInvalidProperty() throws Exception {

		// Register a whiteboard resource with an invalid filter

		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_RESOURCE, Boolean.TRUE);
		properties.put(JAKARTA_RS_EXTENSION_SELECT, "...foo=bar...");

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<EchoResource> reg = context.registerService(
				EchoResource.class, new EchoResource(), properties);

		awaitSelection.getValue();

		FailedResourceDTO[] dtos = runtimeService
				.getRuntimeDTO().failedResourceDTOs;
		assertThat(dtos).isNotNull();
		assertThat(dtos).hasSize(1);
		assertThat(dtos[0].failureReason).isEqualTo(FAILURE_REASON_VALIDATION_FAILED);
		assertThat(dtos[0].serviceId).isEqualTo(reg.getReference().getProperty(SERVICE_ID));

	}

	/**
	 * 151.5 - must be advertised as an acceptable type
	 * 
	 * @throws Exception
	 */
	@Test
	public void testInvalidExtensionType() throws Exception {
		// Register a whiteboard extension as an invalid type
		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_EXTENSION, Boolean.TRUE);

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<OSGiTextMimeTypeCodec> reg = context
				.registerService(OSGiTextMimeTypeCodec.class,
						new OSGiTextMimeTypeCodec(), properties);

		awaitSelection.getValue();

		FailedExtensionDTO[] dtos = runtimeService
				.getRuntimeDTO().failedExtensionDTOs;
		assertThat(dtos).isNotNull();
		assertThat(dtos).hasSize(1);
		assertThat(dtos[0].failureReason).isEqualTo(FAILURE_REASON_NOT_AN_EXTENSION_TYPE);
		assertThat(dtos[0].serviceId).isEqualTo(reg.getReference().getProperty(SERVICE_ID));
	}

	/**
	 * 151.2.2.2 not gettable
	 * 
	 * @throws Exception
	 */
	@Test
	public void testUngettableService() throws Exception {

		// Register a whiteboard resource that fails to get

		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_RESOURCE, Boolean.TRUE);

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<EchoResource> reg = context.registerService(
				EchoResource.class,
				getPrototypeServiceFactory(() -> null, (a, b) -> {}),
				properties);

		awaitSelection.getValue();

		FailedResourceDTO[] dtos = runtimeService
				.getRuntimeDTO().failedResourceDTOs;
		assertThat(dtos).isNotNull();
		assertThat(dtos).hasSize(1);
		assertThat(dtos[0].failureReason).isEqualTo(FAILURE_REASON_SERVICE_NOT_GETTABLE);
		assertThat(dtos[0].serviceId).isEqualTo(reg.getReference().getProperty(SERVICE_ID));

	}

	/**
	 * Section 151.6.1 Clashing names must trigger a failure
	 * 
	 * @throws Exception
	 */
	@Test
	public void testApplicationShadowing() throws Exception {

		// Register a whiteboard application at "/clash"

		Dictionary<String,Object> properties = new Hashtable<>();
		properties.put(JAKARTA_RS_APPLICATION_BASE, "/clash");

		Promise<Void> awaitSelection = helper.awaitModification(runtime, 5000);

		context.registerService(Application.class,
						new SimpleApplication(emptySet(),
								singleton(new WhiteboardResource())),
						properties);

		awaitSelection.getValue();

		// Register a second whiteboard application at "/clash"

		awaitSelection = helper.awaitModification(runtime, 5000);

		ServiceRegistration<Application> reg2 = context
				.registerService(Application.class,
						new SimpleApplication(emptySet(),
								singleton(new WhiteboardResource())),
						properties);

		awaitSelection.getValue();

		FailedApplicationDTO[] dtos = runtimeService
				.getRuntimeDTO().failedApplicationDTOs;
		assertThat(dtos).isNotNull();
		assertThat(dtos).hasSize(1);
		assertThat(dtos[0].failureReason).isEqualTo(FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE);
		assertThat(dtos[0].serviceId).isEqualTo(reg2.getReference().getProperty(SERVICE_ID));
	}
}
