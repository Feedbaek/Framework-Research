package com.example.reload.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.example.reload.ReloadProperties;

/**
 * {@code reload.trigger.mode}가 api 또는 both일 때만 일치한다.
 */
class OnReloadApiCondition extends SpringBootCondition {

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		ReloadProperties.Mode mode = Binder.get(context.getEnvironment())
			.bind("reload.trigger.mode", ReloadProperties.Mode.class)
			.orElse(ReloadProperties.Mode.WATCH);
		return mode.isApi() ? ConditionOutcome.match("reload.trigger.mode is " + mode)
				: ConditionOutcome.noMatch("reload.trigger.mode is " + mode);
	}

}
