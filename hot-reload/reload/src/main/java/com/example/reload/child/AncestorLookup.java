package com.example.reload.child;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;

/**
 * 조상 context의 빈 이름을 선형 시간에 합쳐 주는 bean factory 뷰.
 * <p>
 * Spring의 {@code BeanFactoryUtils.beanNamesForTypeIncludingAncestors}는 부모 이름마다 결과 목록을 선형 탐색하므로
 * 부모 빈 수의 제곱에 비례한다. 세대는 {@code @Aspect}, {@code @ControllerAdvice}를 찾을 때마다 이 경로로 부모의
 * 모든 빈 이름을 합치는데, 부모 빈이 수만 개면 한 번에 1초가 넘는다.
 * <p>
 * 이 뷰는 {@code getBeanNamesForType}이 조상의 이름까지 직접 합쳐 돌려주고 {@code getParentBeanFactory()}는
 * {@code null}을 돌려준다. 그래서 위 유틸리티가 다시 병합하지 않는다. 결과는 같다: 가까운 factory의 로컬 빈이
 * 같은 이름의 조상 빈을 가린다. 나머지 호출은 원본에 그대로 넘기므로 조상 빈의 조회·생성은 원래대로 동작한다.
 * <p>
 * 뷰는 세대의 객체만 참조하고 세대와 함께 사라진다.
 */
final class AncestorLookup implements InvocationHandler {

	private final ListableBeanFactory target;

	ListableBeanFactory target() {
		return this.target;
	}

	private AncestorLookup(ListableBeanFactory target) {
		this.target = target;
	}

	/**
	 * @param target 세대의 context 또는 bean factory
	 * @param view 뷰가 구현할 인터페이스({@code target}이 구현하는 것)
	 */
	static <T extends ListableBeanFactory> T of(T target, Class<? super T> view) {
		@SuppressWarnings("unchecked")
		T proxy = (T) Proxy.newProxyInstance(AncestorLookup.class.getClassLoader(), new Class<?>[] { view },
				new AncestorLookup(target));
		return proxy;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
		try {
			if (method.getName().equals("getParentBeanFactory") && method.getParameterCount() == 0) {
				return null;
			}
			if (method.getName().equals("getBeanNamesForType")) {
				return namesIncludingAncestors(method, arguments);
			}
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "equals" -> proxy == arguments[0];
					case "hashCode" -> System.identityHashCode(proxy);
					default -> "AncestorLookup of " + this.target;
				};
			}
			return method.invoke(this.target, arguments);
		}
		catch (InvocationTargetException ex) {
			throw ex.getCause();
		}
	}

	private String[] namesIncludingAncestors(Method method, Object[] arguments) throws ReflectiveOperationException {
		List<HierarchicalBeanFactory> nearer = new ArrayList<>();
		Set<String> names = new LinkedHashSet<>();
		BeanFactory factory = this.target;
		while (factory instanceof ListableBeanFactory listable) {
			for (String name : (String[]) method.invoke(listable, arguments)) {
				if (nearer.stream().noneMatch((candidate) -> candidate.containsLocalBean(name))) {
					names.add(name);
				}
			}
			if (!(factory instanceof HierarchicalBeanFactory hierarchical)) {
				break;
			}
			nearer.add(hierarchical);
			factory = hierarchical.getParentBeanFactory();
		}
		return names.toArray(String[]::new);
	}

}
