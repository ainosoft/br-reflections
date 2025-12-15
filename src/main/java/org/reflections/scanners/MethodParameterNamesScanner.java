package org.reflections.scanners;

import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.MethodInfo;
import org.reflections.util.JavassistHelper;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
// ADD these imports:
import javassist.bytecode.MethodParametersAttribute;
import java.util.Objects;

public class MethodParameterNamesScanner implements Scanner {

	@Override
	public List<Map.Entry<String, String>> scan(ClassFile classFile) {
		List<Map.Entry<String, String>> entries = new ArrayList<>();
		for (MethodInfo method : classFile.getMethods()) {
			String key = JavassistHelper.methodName(classFile, method);
			String value = getString(method);
			if (!value.isEmpty()) {
				entries.add(entry(key, value));
			}
		}
		return entries;
	}

	private String getString(MethodInfo method) {
		// how many parameters the method actually has (from descriptor)
		final int paramCount = JavassistHelper.getParameters(method).size();
		if (paramCount == 0) {
			return "";
		}

		// 1) Try Java 8+ MethodParameters attribute (requires -parameters)
		try {
			MethodParametersAttribute mp = (MethodParametersAttribute) method
					.getAttribute(MethodParametersAttribute.tag);
			if (mp != null && mp.size() > 0) {
				// MethodParameters lists only real parameters; no "this" slot
				final int n = Math.min(mp.size(), paramCount);
				List<String> names = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					// name(i) returns a CP index; resolve to UTF8
					names.add(method.getConstPool().getUtf8Info(mp.name(i)));
				}
				String joined = names.stream().filter(Objects::nonNull).filter(name -> !name.startsWith("this$"))
						.collect(Collectors.joining(", "));
				if (!joined.isEmpty()) {
					return joined;
				}
			}
		} catch (Throwable ignore) {
			// fall back if attribute missing or older Javassist, etc.
		}

		// 2) Fallback to LocalVariableTable (requires -g:vars)
		CodeAttribute code = method.getCodeAttribute();
		if (code == null) {
			// abstract/native: no code → no LVT
			return "";
		}
		LocalVariableAttribute lvt = (LocalVariableAttribute) code.getAttribute(LocalVariableAttribute.tag);
		if (lvt == null) {
			return "";
		}

		// LVT includes "this" for non-static methods; skip it
		int shift = Modifier.isStatic(method.getAccessFlags()) ? 0 : 1;
		return IntStream.range(shift, paramCount + shift).mapToObj(i -> {
			try {
				int idx = lvt.nameIndex(i);
				return idx == 0 ? null : method.getConstPool().getUtf8Info(idx);
			} catch (Throwable t) {
				return null; // be tolerant of short/odd LVTs
			}
		}).filter(Objects::nonNull).filter(name -> !name.startsWith("this$")).collect(Collectors.joining(", "));
	}
}