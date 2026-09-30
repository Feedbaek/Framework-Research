package com.example.reload.autoconfigure;

import com.example.reload.ReloadProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class OnHybridCondition implements Condition {
	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return Binder.get(context.getEnvironment()).bind("reload", ReloadProperties.class)
				.orElseGet(ReloadProperties::new).isHybrid();
	}
}
