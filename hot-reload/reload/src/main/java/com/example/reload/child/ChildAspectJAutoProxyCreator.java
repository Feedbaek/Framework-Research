package com.example.reload.child;

import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * 자식 세대의 auto-proxy creator. 부모 context의 {@code Advisor} bean은 쓰지 않는다.
 * <p>
 * 기본 {@link AnnotationAwareAspectJAutoProxyCreator}는 부모까지 올라가 {@code Advisor} bean을 찾는다.
 * 부모의 트랜잭션·캐시 advisor 같은 것이 자식 bean에 적용되면 메서드별 메타데이터 캐시가 부모 쪽
 * 인스턴스에 쌓여 이전 세대의 클래스로더를 붙잡는다(누수). 자식에 필요한 advisor는 자식에 정의된
 * 것({@link ChildInfrastructureConfiguration} 또는 애플리케이션의 {@code @Enable*})만 쓴다.
 * <p>
 * {@code @Aspect} bean은 부모 것도 그대로 적용한다. advisor 인스턴스를 자식이 새로 만들기 때문에
 * 캐시가 자식과 함께 사라진다.
 */
public class ChildAspectJAutoProxyCreator extends AnnotationAwareAspectJAutoProxyCreator {

	private ConfigurableListableBeanFactory beanFactory;

	@Override
	protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		super.initBeanFactory(beanFactory);
		this.beanFactory = beanFactory;
	}

	@Override
	protected boolean isEligibleAdvisorBean(String beanName) {
		return this.beanFactory.containsBeanDefinition(beanName) && super.isEligibleAdvisorBean(beanName);
	}

}
