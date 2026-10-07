package com.elfmcys.ysm.geckolib3.core.molang;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.debug.CustomDebugSource;
import com.elfmcys.ysm.client.event.RenderDebugAnimationScreenEvent;
import com.elfmcys.ysm.geckolib3.core.molang.binding.PrimaryBinding;
import com.elfmcys.ysm.geckolib3.core.molang.value.FloatValue;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.core.molang.value.MolangValue;
import com.elfmcys.ysm.molang.MolangEngine;
import com.elfmcys.ysm.molang.parser.ParseException;
import net.minecraft.util.text.TextComponentTranslation;

import java.util.Map;

public class MolangParser {
    private final MolangEngine engine;
    private final PrimaryBinding primaryBinding;

    public MolangParser(Map<String, Object> extraBindings) {
        primaryBinding = new PrimaryBinding(extraBindings);
        engine = MolangEngine.fromCustomBinding(primaryBinding);
    }

    public IValue parseExpression(String molangExpression) {
        return parseExpression(molangExpression, false);
    }

    public IValue parseExpression(String molangExpression, boolean isUserFunc) {
        try {
            return parseExpressionUnsafe(molangExpression, isUserFunc);
        } catch (Exception e) {
            if (RenderDebugAnimationScreenEvent.isEnabled()) {
                YesSteveModel.LOGGER.error("Failed to parse molang expression: {}\n{}", e.getMessage(), molangExpression);
                CustomDebugSource.INSTANCE.print(new TextComponentTranslation("error.yes_steve_model.parse_molang_exp")
                                .appendText(e.getMessage())
                                .appendText("\n----------------------\n")
                                .appendText(molangExpression.replace("\r\n", "\n").replace("\r", "\n"))
                                .appendText("\n----------------------"));
            } else {
                YesSteveModel.LOGGER.debug("Failed to parse molang expression: {}\n{}", e.getMessage(), molangExpression);
            }
            return FloatValue.ZERO;
        }
    }

    public IValue parseExpressionUnsafe(String molangExpression, boolean isUserFunc) throws ParseException {
        MolangValue value = new MolangValue(engine.parse(isUserFunc ? filterComment(molangExpression) : molangExpression), isUserFunc);
        primaryBinding.resetTransient();
        return value;
    }

    // C 风格注释
    private static String filterComment(String exp) {
        StringBuilder result = new StringBuilder(exp.length());
        boolean blockComment = false;
        boolean lineComment = false;
        boolean string = false;
        for (int i = 0; i < exp.length(); i++) {
            char c = exp.charAt(i);
            if (string) {
                if (c == '\'') {
                    string = false;
                }
                result.append(c);
                continue;
            }
            if (lineComment) {
                if (c == '\r' || c == '\n') {
                    lineComment = false;
                    result.append('\n');
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && i + 1 < exp.length()) {
                    char next = exp.charAt(i + 1);
                    if (next == '/') {
                        blockComment = false;
                        i++;
                    }
                }
                continue;
            }
            if (c == '\'') {
                string = true;
                result.append('\'');
                continue;
            }
            if (c == '/' && i + 1 < exp.length()) {
                char next = exp.charAt(i + 1);
                if (next == '/') {
                    lineComment = true;
                    i++;
                    continue;
                } else if (next == '*') {
                    blockComment = true;
                    i++;
                    continue;
                }
            }
            result.append(c);
        }

        return result.toString();
    }

    public IValue getConstant(double value) {
        return new FloatValue((float) value);
    }

    public void reset() {
        primaryBinding.resetScoped();
    }
}
