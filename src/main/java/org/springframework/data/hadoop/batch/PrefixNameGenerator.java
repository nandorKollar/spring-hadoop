/*
 * Copyright 2011 the original author or authors.
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
package org.springframework.data.hadoop.batch;

import org.springframework.util.StringUtils;

/**
 * Simple name generator that appends a prefix to the given name. 
 * 
 * @author Costin Leau
 */
public class PrefixNameGenerator implements NameGenerator {

	private String prefix = "";

	public void setPrefix(String prefix) {
		this.prefix = (StringUtils.hasText(prefix) ? prefix : "");
	}

	public String generate(String original) {
		if (prefix.endsWith("/") && original.startsWith("/")) {
			return prefix.substring(0, prefix.length() - 1).concat(original);
		}
		return prefix.concat(original);
	}
}