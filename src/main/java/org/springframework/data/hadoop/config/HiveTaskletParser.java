/*
 * Copyright 2011-2012 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.hadoop.config;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.hadoop.hive.HiveScript;
import org.springframework.data.hadoop.hive.HiveTasklet;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

/**
 * Parser for 'hive-tasklet' element.
 *
 * @author Costin Leau
 */
class HiveTaskletParser extends AbstractImprovedSimpleBeanDefinitionParser {

	@Override
	protected Class<?> getBeanClass(Element element) {
		return HiveTasklet.class;
	}


	@Override
	protected boolean isEligibleAttribute(String attributeName) {
		return (!"hive-client-ref".equals(attributeName)) && super.isEligibleAttribute(attributeName);
	}

	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
		// parse attributes using conventions
		super.doParse(element, parserContext, builder);

		builder.addPropertyValue("hiveClientName", element.getAttribute("hive-client-ref"));

		// parse scripts
		Collection<Object> scripts = parseScripts(parserContext, element);
		if (!CollectionUtils.isEmpty(scripts)) {
			builder.addPropertyValue("scripts", scripts);
		}
	}

	static Collection<Object> parseScripts(ParserContext context, Element element) {
		Collection<Element> children = DomUtils.getChildElementsByTagName(element, "script");

		if (!children.isEmpty()) {
			Collection<Object> defs = new ManagedList<Object>(children.size());

			for (Element child : children) {
				// parse source
				String location = child.getAttribute("location");
				String inline = DomUtils.getTextValue(child);
				boolean hasScriptInlined = StringUtils.hasText(inline);

				GenericBeanDefinition def = new GenericBeanDefinition();
				def.setSource(child);
				def.setBeanClass(HiveScript.class);

				Object resource = null;

				if (StringUtils.hasText(location)) {
					if (hasScriptInlined) {
						context.getReaderContext().error("cannot specify both 'location' and a nested script; use only one", element);
					}
					resource = location;
				}
				else {
					if (!hasScriptInlined) {
						context.getReaderContext().error("no 'location' or nested script specified", element);
					}

					byte[] bytes = null;
					try{
						bytes = inline.getBytes("UTF-8");	
					} catch (IOException ex){
						context.getReaderContext().warning("cannot convert inlined script using 'utf-8', falling back to platform default", element);
						bytes = inline.getBytes();
					}
					
					
					resource = new ByteArrayResource(bytes, "resource for inlined script");
				}

				def.getConstructorArgumentValues().addIndexedArgumentValue(0, resource, Resource.class.getName());
				String args = DomUtils.getChildElementValueByTagName(child, "arguments");

				if (args != null) {
					def.getConstructorArgumentValues().addIndexedArgumentValue(1, new LinkedProperties(args));
				}
				defs.add(def);

			}

			return defs;
		}

		return Collections.emptyList();
	}
}