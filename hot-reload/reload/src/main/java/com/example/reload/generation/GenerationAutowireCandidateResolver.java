package com.example.reload.generation;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;

/**
 * 자식 세대의 autowire 후보 판단. 부모에 정의된 bean은 그 정의를 가진 factory의 resolver가 판단한다.
 * <p>
 * Spring은 자식 factory에 없는 후보를 부모 factory로 넘기면서 자식의 resolver를 그대로 쓴다. 자식 resolver는
 * 부모의 scoped proxy가 가리키는 {@code scopedTarget.*} 정의를 찾지 못해 제네릭 정보가 없는 proxy 타입을 얻고, 그
 * 타입을 부모의 merged bean definition({@code targetType})에 캐시한다. 그러면 부모가 나중에 같은 제네릭 타입의 후보
 * 중 하나를 고를 때 오염된 후보가 엄격한 매칭에서 빠져 파라미터 이름과 다른 bean이 주입된다(예: ProObject 22의
 * {@code @RefreshScope} {@code Map<String, Boolean>} bean).
 */
class GenerationAutowireCandidateResolver extends ContextAnnotationAutowireCandidateResolver {

	private DefaultListableBeanFactory beanFactory;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		super.setBeanFactory(beanFactory);
		this.beanFactory = (beanFactory instanceof DefaultListableBeanFactory dlbf) ? dlbf : null;
	}

	@Override
	public boolean isAutowireCandidate(BeanDefinitionHolder bdHolder, DependencyDescriptor descriptor) {
		AutowireCandidateResolver owner = ownerResolver(BeanFactoryUtils.transformedBeanName(bdHolder.getBeanName()));
		return (owner != null) ? owner.isAutowireCandidate(bdHolder, descriptor)
				: super.isAutowireCandidate(bdHolder, descriptor);
	}

	private AutowireCandidateResolver ownerResolver(String beanName) {
		if (this.beanFactory == null || this.beanFactory.containsBeanDefinition(beanName)) {
			return null;
		}
		BeanFactory parent = this.beanFactory.getParentBeanFactory();
		while (parent instanceof DefaultListableBeanFactory factory) {
			if (factory.containsBeanDefinition(beanName)) {
				return factory.getAutowireCandidateResolver();
			}
			parent = factory.getParentBeanFactory();
		}
		return null;
	}

}
