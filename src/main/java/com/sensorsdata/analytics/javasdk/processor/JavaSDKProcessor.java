package com.sensorsdata.analytics.javasdk.processor;

import com.sensorsdata.analytics.javasdk.SensorsAnalyticsAPI;
import com.sensorsdata.analytics.javasdk.annotation.*;
import com.sensorsdata.analytics.javasdk.processor.exceptions.InvalidSizeException;
import com.sensorsdata.analytics.javasdk.processor.exceptions.UnqualifiedMethodException;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import org.apache.http.util.TextUtils;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class JavaSDKProcessor extends AbstractProcessor {
    // 打印 log
    private Messager messager;
    // 抽象语法树
    private JavacTrees trees;
    // 封装了创建AST节点的一些方法
    private TreeMaker treeMaker;
    // 提供了创建标识符的一些方法
    private Names names;

    @Override
    public void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.trees = JavacTrees.instance(processingEnv);
        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    private JCExpression loginId;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!roundEnv.processingOver()) {
            try {
                messager.printMessage(Diagnostic.Kind.NOTE, "神策 Java SDK 埋点注解开始处理");
                loginId = buildLoginId(roundEnv);
                processAllAnnotations(roundEnv);
                messager.printMessage(Diagnostic.Kind.NOTE, "神策 Java SDK 埋点注解结束处理");
            } catch (Exception e) {
                printStacktrace(e);
            }
        }
        return true;
    }

    // 统一处理 @LoginIdFrom 以外的、所有需要插入代码的注解
    private void processAllAnnotations(RoundEnvironment roundEnv) throws InvalidSizeException {
        Set<? extends Element> initSDKElements = roundEnv.getElementsAnnotatedWith(InitSensorsAnalytics.class);
        if(initSDKElements.size() > 1){
            throw new InvalidSizeException("最多只能有 1 个方法用 @InitSensorsAnalytics 注解修饰，目前有 " + initSDKElements.size() + " 个。");
        }
        if (initSDKElements.size() == 0) {
            messager.printMessage(Diagnostic.Kind.WARNING, "@InitSensorsAnalytics 注解没有被使用");
        }

        Set<Element> allElements = new HashSet<>(initSDKElements);
        allElements.addAll(roundEnv.getElementsAnnotatedWith(Track.class));
        allElements.addAll(roundEnv.getElementsAnnotatedWith(Profile.class));
        allElements.addAll(roundEnv.getElementsAnnotatedWith(Item.class));
        allElements.addAll(roundEnv.getElementsAnnotatedWith(TrackSignUp.class));
        // 为了保证同一个方法上注解的顺序与生成埋点代码的顺序一致，此处将所有神策注解的方法汇总处理
        for (Element element : allElements) {
            java.util.List<? extends AnnotationMirror> annotationMirrors = element.getAnnotationMirrors();
            ListBuffer<JCStatement> statements = new ListBuffer<>();
            for (AnnotationMirror mirror : annotationMirrors) {
                if (Track.class.getCanonicalName().equals(mirror.getAnnotationType().toString())) {
                    statements.appendList(processTrack((MethodSymbol) element));
                } else if (Profile.class.getCanonicalName().equals(mirror.getAnnotationType().toString())) {
                    statements.appendList(processProfile((MethodSymbol) element));
                } else if (TrackSignUp.class.getCanonicalName().equals(mirror.getAnnotationType().toString())) {
                    statements.appendList(processTrackSignUp((MethodSymbol) element));
                } else if (Item.class.getCanonicalName().equals(mirror.getAnnotationType().toString())) {
                    statements.appendList(processItem((MethodSymbol) element));
                } else if (InitSensorsAnalytics.class.getCanonicalName().equals(mirror.getAnnotationType().toString())) {
                    statements.appendList(processInitSensorsAnalytics((MethodSymbol) element));
                } else {
                    // doNothing
                }
            }

            // 将同一个方法上所有埋点注解生成的代码汇成一个代码块，加上 try catch 语句后添加到原方法头
            JCMethodDecl tree = trees.getTree((MethodSymbol) element);
            JCTry jcTry = buildCatchException(treeMaker.Block(0, statements.toList()));
            ListBuffer<JCStatement> jcStatements = new ListBuffer<>();
            jcStatements.append(jcTry);
            jcStatements.appendList(tree.body.getStatements());
            tree.body = treeMaker.Block(0, jcStatements.toList());
        }
    }

    /**
     * 处理 @LoginIdFrom 注解
     * @param roundEnv 上下文
     * @return 该注解修饰方法对应的 JCExpression
     */
    private JCExpression buildLoginId(RoundEnvironment roundEnv) throws InvalidSizeException, UnqualifiedMethodException {
        Set<? extends Element> loginIdFromElements = roundEnv.getElementsAnnotatedWith(LoginIdFrom.class);
        if(loginIdFromElements.size() > 1){
            throw new InvalidSizeException("最多只能有 1 个方法用 @LoginIdFrom 注解修饰，目前有 " + loginIdFromElements.size() + " 个。");
        }
        if (loginIdFromElements.size() == 0) {
            messager.printMessage(Diagnostic.Kind.WARNING, "@LoginIdFrom 注解没有被使用");
            return null;
        }else{
            if(loginIdFromElements.iterator().hasNext()){
                Element loginIdFromElement = loginIdFromElements.iterator().next();
                Set<Modifier> modifiers = loginIdFromElement.getModifiers();
                if (modifiers.contains(Modifier.PUBLIC) && modifiers.contains(Modifier.STATIC)) {
                    MethodSymbol method = (MethodSymbol) loginIdFromElement;
                    if (method.params != null && method.params.size() == 0 && "java.lang.String".equals(method.getReturnType().toString())) {
                        return treeMaker.Exec(treeMaker.Apply(List.nil(), accessMember(method.owner.toString() + "." + method.name), List.nil())).expr;
                    } else {
                        throw new UnqualifiedMethodException("@LoginIdFrom 只能用于无入参且返回值为 java.lang.String 类型的方法");
                    }
                } else {
                    throw new UnqualifiedMethodException("@LoginIdFrom 只能用于 public static 类型的方法");
                }
            }
        }
        return null;
    }

    /**
     * 处理 @InitSensorsAnalytics 注解
     * <p>
     * 主要就是插入以下三行初始化代码
     * Method thisMethod_SensorsAnalyticsSDK = 注解所在类名.class.getMethod("注解修饰的方法名",注解修饰的方法入参.class);
     * InitSensorsAnalytics initSDKAnnotation_SensorsAnalyticsSDK = thisMethod_SensorsAnalyticsSDK.getAnnotation(InitSensorsAnalytics.class);
     * SensorsAnalyticsAPI.startWithAnnotation(initSDKAnnotation_SensorsAnalyticsSDK);
     */
    private List<JCStatement> processInitSensorsAnalytics(MethodSymbol method) {
        // 生成第一行代码：Method thisMethod_SensorsAnalyticsSDK = 注解所在类名.class.getMethod("注解修饰的方法名",注解修饰的方法入参.class);
        ListBuffer<JCExpression> args = new ListBuffer<>();
        args.append(treeMaker.Literal(method.name.toString()));
        for (VarSymbol var : method.params) {
            args.append(accessClass(var.type));
        }

        JCVariableDecl varDef_thisMethod =
                makeVarDef(
                        "java.lang.reflect.Method",
                        "thisMethod_SensorsAnalyticsSDK",
                        treeMaker.Exec(
                                treeMaker.Apply(
                                        List.nil(),
                                        accessMember(method.owner.toString() + ".class.getMethod"),
                                        args.toList())).expr);

        // 生成第二行代码：InitSensorsAnalytics initSDKAnnotation_SensorsAnalyticsSDK = thisMethod_SensorsAnalyticsSDK.getAnnotation(InitSensorsAnalytics.class);
        JCVariableDecl varDef_initSDKAnnotation = makeVarDef(
                InitSensorsAnalytics.class.getCanonicalName(),
                "initSDKAnnotation_SensorsAnalyticsSDK",
                treeMaker.Exec(
                        treeMaker.Apply(
                                List.nil(),
                                treeMaker.Select(treeMaker.Ident(varDef_thisMethod.name), names.fromString("getAnnotation")),
                                List.of(accessMember(InitSensorsAnalytics.class.getCanonicalName() + ".class"))
                        )
                ).expr);

        // 生成第四行代码：SensorsAnalyticsAPI.startWithAnnotation(initSDKAnnotation_SensorsAnalyticsSDK);
        JCExpressionStatement expr_init = treeMaker.Exec(
                treeMaker.Apply(
                        List.nil(),
                        accessMember(SensorsAnalyticsAPI.class.getCanonicalName() + ".startWithAnnotation"),
                        List.of(treeMaker.Ident(varDef_initSDKAnnotation.name))
                )
        );


        ListBuffer<JCStatement> processStatements = new ListBuffer<>();
        processStatements.append(varDef_thisMethod);
        processStatements.append(varDef_initSDKAnnotation);
        processStatements.append(expr_init);
        return processStatements.toList();
    }

    /**
     * 处理 @Track 注解
     *
     * @param method 注解修饰的方法
     * @return 返回调用 track 的上下文代码
     * <p>
     * 主要是插入以下几行 track 相关代码
     * Map track_properties_SensorsAnalyticsSDK = new HashMap();
     * ...
     * 很多的 track_properties_SensorsAnalyticsSDK.put();
     * ...
     * SensorsAnalyticsAPI.sharedInstance.track(distinctId, isLoginId, eventName, track_properties_SensorsAnalyticsSDK);
     */
    private List<JCStatement> processTrack(MethodSymbol method) {
        ListBuffer<JCStatement> processStatements = new ListBuffer<>();
        // 插入第一行代码 Map track_properties_SensorsAnalyticsSDK = new HashMap();
        JCVariableDecl varPropertiesDef = makeVarDef("java.util.Map", "track_properties_SensorsAnalyticsSDK", buildNewHashMap());
        processStatements.append(varPropertiesDef);

        // 插入很多行 track_properties_SensorsAnalyticsSDK.put 的代码
        Track trackAnnotation = method.getAnnotation(Track.class);
        // 如果 includeParams 为 false 则不处理方法入参
        processStatements.appendList(processProperties(trackAnnotation.includeParams()? method.params: List.nil(), trackAnnotation.properties(), varPropertiesDef));

        // 插入最后一行代码 SensorsAnalyticsAPI.sharedInstance.track(distinctId, isLoginId, eventName, track_properties_SensorsAnalyticsSDK);
        JCExpression distinctId = processDistinctId(trackAnnotation.distinctId());
        JCLiteral isLoginId = treeMaker.Literal(trackAnnotation.isLoginId());
        JCLiteral eventName = TextUtils.isBlank(trackAnnotation.eventName()) ? treeMaker.Literal(method.name.toString()) : treeMaker.Literal(trackAnnotation.eventName());
        JCIdent properties = treeMaker.Ident(varPropertiesDef.name);
        processStatements.append(treeMaker.Exec(
                treeMaker.Apply(
                        List.nil(),
                        treeMaker.Select(buildSharedInstance(), names.fromString("track")),
                        List.of(distinctId, isLoginId, eventName, properties)
                )
        ));
        if(trackAnnotation.flush()){
            processStatements.append(buildFlush());
        }
        return processStatements.toList();
    }

    /**
     * 处理 @Profile 注解
     *
     * @param method 注解修饰的方法
     * @return 返回调用 profile_* 的上下文代码
     * <p>
     * 主要是插入以下几行 profile_* 相关代码
     * Map profile_properties_SensorsAnalyticsSDK = new HashMap();
     * ...
     * 很多的 profile_properties_SensorsAnalyticsSDK.put();
     * ...
     * SensorsAnalyticsAPI.sharedInstance.profile_*(distinctId, isLoginId, profile_properties_SensorsAnalyticsSDK);
     */
    private List<JCStatement> processProfile(MethodSymbol method) {
        ListBuffer<JCStatement> processStatements = new ListBuffer<>();
        // 插入第一行代码 Map profile_properties_SensorsAnalyticsSDK = new HashMap();
        JCVariableDecl varPropertiesDef = makeVarDef("java.util.Map", "profile_properties_SensorsAnalyticsSDK", buildNewHashMap());
        processStatements.append(varPropertiesDef);

        // 插入很多行 profile_properties_SensorsAnalyticsSDK.put 的代码
        Profile profileAnnotation = method.getAnnotation(Profile.class);
        // 如果 includeParams 为 false 则不处理方法入参
        processStatements.appendList(processProperties(profileAnnotation.includeParams()? method.params: List.nil(), profileAnnotation.properties(), varPropertiesDef));

        // 插入最后一行代码 SensorsAnalyticsAPI.sharedInstance.profile_*(distinctId, isLoginId, profile_properties_SensorsAnalyticsSDK);
        JCExpression distinctId = processDistinctId(profileAnnotation.distinctId());
        JCLiteral isLoginId = treeMaker.Literal(profileAnnotation.isLoginId());
        JCIdent properties = treeMaker.Ident(varPropertiesDef.name);

        String profileMethodName = "";
        switch (profileAnnotation.type()) {
            case SET: profileMethodName = "profileSet";break;
            case SET_ONCE: profileMethodName = "profileSetOnce";break;
            case APPEND: profileMethodName = "profileAppend";break;
            case INCREMENT: profileMethodName = "profileIncrement";break;
        }

        processStatements.append(treeMaker.Exec(
                treeMaker.Apply(
                        List.nil(),
                        treeMaker.Select(buildSharedInstance(), names.fromString(profileMethodName)),
                        List.of(distinctId,isLoginId,properties)
                )
        ));

        if(profileAnnotation.flush()){
            processStatements.append(buildFlush());
        }
        return processStatements.toList();
    }

    /**
     * 处理 @Item 注解
     *
     * @param method 注解修饰的方法
     * @return 返回调用 item_* 的上下文代码
     * <p>
     * 主要是插入以下几行 item_* 相关代码
     * Map item_properties_SensorsAnalyticsSDK = new HashMap();
     * ...
     * 很多的 item_properties_SensorsAnalyticsSDK.put();
     * ...
     * SensorsAnalyticsAPI.sharedInstance.item_*(itemType, itemId, item_properties_SensorsAnalyticsSDK);
     */
    private List<JCStatement> processItem(MethodSymbol method) {
        ListBuffer<JCStatement> processStatements = new ListBuffer<>();
        // 插入第一行代码 Map item_properties_SensorsAnalyticsSDK = new HashMap();
        JCVariableDecl varPropertiesDef = makeVarDef("java.util.Map", "item_properties_SensorsAnalyticsSDK", buildNewHashMap());
        processStatements.append(varPropertiesDef);

        // 插入很多行 item_properties_SensorsAnalyticsSDK.put 的代码
        Item itemAnnotation = method.getAnnotation(Item.class);
        // 如果 includeParams 为 false 则不处理方法入参
        processStatements.appendList(processProperties(itemAnnotation.includeParams()? method.params: List.nil(), itemAnnotation.properties(), varPropertiesDef));

        // 插入最后一行代码 SensorsAnalyticsAPI.sharedInstance.item_*(distinctId, isLoginId, profile_properties_SensorsAnalyticsSDK);
        JCExpression itemType = itemAnnotation.itemType().startsWith("@") ? parseExpr(itemAnnotation.itemType().substring(1)): treeMaker.Literal(itemAnnotation.itemType());
        JCExpression itemId = itemAnnotation.itemId().startsWith("@") ? parseExpr(itemAnnotation.itemId().substring(1)): treeMaker.Literal(itemAnnotation.itemId());
        JCIdent properties = treeMaker.Ident(varPropertiesDef.name);

        String itemMethodName = "";
        switch (itemAnnotation.type()) {
            case SET: itemMethodName = "itemSet";break;
            case DELETE: itemMethodName = "itemDelete";break;
        }

        processStatements.append(treeMaker.Exec(
                treeMaker.Apply(
                        List.nil(),
                        treeMaker.Select(buildSharedInstance(), names.fromString(itemMethodName)),
                        List.of(itemType, itemId, properties)
                )
        ));

        if(itemAnnotation.flush()){
            processStatements.append(buildFlush());
        }
        return processStatements.toList();
    }

    /**
     * 处理 @TrackSignUp 注解
     *
     * @param method 注解修饰的方法
     * @return 返回调用 trackSignUp 的上下文代码
     * <p>
     * 主要是插入以下几行 trackSignUp 相关代码
     * SensorsAnalyticsAPI.sharedInstance.track(loginId, anonymousId);
     */
    private List<JCStatement> processTrackSignUp(MethodSymbol method) {
        ListBuffer<JCStatement> processStatements = new ListBuffer<>();

        TrackSignUp signUpAnnotation = method.getAnnotation(TrackSignUp.class);
        JCExpression loginId = processDistinctId(signUpAnnotation.loginId());
        JCExpression anonymousId = signUpAnnotation.anonymousId().startsWith("@") ? parseExpr(signUpAnnotation.anonymousId().substring(1)): treeMaker.Literal(signUpAnnotation.anonymousId());
        // 插入代码 SensorsAnalyticsAPI.sharedInstance.trackSignUp(loginId, anonymousId);
        processStatements.append(treeMaker.Exec(
                treeMaker.Apply(
                        List.nil(),
                        treeMaker.Select(buildSharedInstance(), names.fromString("trackSignUp")),
                        List.of(loginId, anonymousId)
                )
        ));

        if(signUpAnnotation.flush()){
            processStatements.append(buildFlush());
        }
        return processStatements.toList();
    }

    /**
     * 将 distinctId 字符串处理成 JCExpression
     * 特殊处理 @ 开头的引用字符串
     * @param value 字符串
     * @return distinctId 对应的 JCExpression
     */
    private JCExpression processDistinctId(String value){
        if(TextUtils.isBlank(value)){
            return loginId;
        }else{
            if(value.startsWith("@")){
                return parseExpr(value.substring(1));
            }else {
                return treeMaker.Literal(value);
            }
        }
    }

    /**
     * 生成 properties 相关的语句
     * properties 有两个来源：
     * 1. 原方法入参
     * 2. 原方法埋点注解的 Property 数组属性
     *
     * @param methodParams     原方法入参
     * @param properties       原方法埋点注解的 Property 数组
     * @param varPropertiesDef 给 properties 定义的临时变量
     * @return 返回 properties 生成所需的语句
     */
    private List<JCStatement> processProperties(List<VarSymbol> methodParams, Property[] properties, JCVariableDecl varPropertiesDef) {
        ListBuffer<JCStatement> processStatements = new ListBuffer<>();
        for (VarSymbol param : methodParams) {
            Property paramAnnotation = param.getAnnotation(Property.class);
            processStatements.append(treeMaker.Exec(
                    treeMaker.Apply(
                            List.nil(),
                            treeMaker.Select(treeMaker.Ident(varPropertiesDef.name), names.fromString("put")),
                            // 方法入参对应的 property key 有两种可能：1. 入参名；2. 修饰入参的 @Property 注解的 key 属性
                            List.of(treeMaker.Literal(paramAnnotation != null && !TextUtils.isBlank(paramAnnotation.key()) ? paramAnnotation.key() : param.name.toString()), treeMaker.Ident(param)))
            ));
        }

        for (Property propertyAnnotation : properties) {
            String value = propertyAnnotation.value();
            JCExpression valueExpr = null;
            if (TextUtils.isBlank(propertyAnnotation.key()))
                continue;

            if (value.startsWith("@")) {
                // value 当成引用表达式去解析
                valueExpr = parseExpr(value.substring(1));
            } else if ("true".equals(value.toLowerCase()) || "false".equals(value.toLowerCase())) {
                // value 当成布尔值去解析
                valueExpr = treeMaker.Literal(Boolean.parseBoolean(value));
            } else {
                try {
                    // value 当成数值去解析
                    // 这里先 new BigDecimal(value) 是为了在编译时就判断此字符串是否可以转化成数字
                    new BigDecimal(value);
                    valueExpr = treeMaker.NewClass(null, List.nil(), accessMember("java.math.BigDecimal"), List.of(treeMaker.Literal(value)), null);
                } catch (NumberFormatException e1) {
                    // value 当成普通字符串
                    valueExpr = treeMaker.Literal(value);
                }
            }

            processStatements.append(treeMaker.Exec(
                    treeMaker.Apply(
                            List.nil(),
                            treeMaker.Select(treeMaker.Ident(varPropertiesDef.name), names.fromString("put")),
                            List.of(treeMaker.Literal(propertyAnnotation.key()), valueExpr))
            ));
        }

        return processStatements.toList();
    }

    /**
     * 解析复杂的引用表达式，例如 value = "@Utils.getUserId(user)"
     *
     * @param exprStr 表达式字符串，不包含开头的字符 @
     * @return 表达式字符串所对应的逻辑代码调用
     */
    private JCExpression parseExpr(String exprStr) {
        exprStr = exprStr.trim();
        if (exprStr.contains("(")) {
            String paramsStr = exprStr.substring(exprStr.indexOf("(") + 1, exprStr.lastIndexOf(")"));
            String methodStr = exprStr.substring(0, exprStr.indexOf("("));
            if(TextUtils.isBlank(paramsStr)){
                return treeMaker.Exec(treeMaker.Apply(List.nil(), accessMember(methodStr), List.nil())).expr;
            }else{
                String[] paramsArray = paramsStr.split(",");
                ListBuffer<JCExpression>  paramExprArray = new ListBuffer<>();
                for (String params : paramsArray) {
                    if (params.contains("(")) {
                        paramExprArray.append(parseExpr(params.trim()));
                    } else {
                        paramExprArray.append(accessMember(params.trim()));
                    }
                }
                return treeMaker.Exec(treeMaker.Apply(List.nil(), accessMember(methodStr), paramExprArray.toList())).expr;
            }
        } else {
            return accessMember(exprStr);
        }
    }

    /**
     * 生成 try catch 语句捕获异常
     * 注意：如果 body 没有内容可能导致此 try catch 被优化掉，也就是插码失败
     *
     * @param body 需要被捕获异常的方法体
     * @return try{
     * body
     * }catch(Exception e){
     * e.printStackTrace()
     * }
     */
    private JCTry buildCatchException(JCBlock body) {
        JCBlock catchBlock = treeMaker.Block(0, List.of(
                treeMaker.Exec(
                        treeMaker.Apply(
                                List.nil(),
                                accessMember("e.printStackTrace"),
                                List.nil()
                        )
                )
        ));

        return treeMaker.Try(
                body,
                List.of(
                        treeMaker.Catch(
                                treeMaker.VarDef(
                                        treeMaker.Modifiers(0), names.fromString("e"), accessMember("java.lang.Exception"),
                                        null),
                                catchBlock)
                ),
                null);
    }

    /**
     * 生成 SDK 单例的引用
     * <p>
     * 生成的代码相当于：SensorsAnalyticsAPI.sharedInstance()
     */
    private JCExpression buildSharedInstance() {
        return treeMaker.Exec(
                treeMaker.Apply(
                        List.nil(),
                        accessMember(SensorsAnalyticsAPI.class.getCanonicalName() + ".sharedInstance"),
                        List.nil())).expr;
    }

    /**
     * 生成新的 HashMap 对象
     * <p>
     * 生成的代码相当于 new HashMap()
     *
     * @return 新 HashMap 对象的 JCExpression
     */
    private JCExpression buildNewHashMap() {
        return treeMaker.NewClass(
                null,
                List.nil(),
                accessMember("java.util.HashMap"),
                List.nil(),
                null);
    }

    /**
     * 生成 flush 语句
     * <p>
     * 生成的代码相当于：SensorsAnalyticsAPI.sharedInstance().flush()
     */
    private JCStatement buildFlush(){
        return treeMaker.Exec(
                treeMaker.Apply(
                        List.nil(),
                        treeMaker.Select(buildSharedInstance(),names.fromString("flush")),
                        List.nil()));
    }

    /**
     * 打印堆栈信息到 messager
     * @param e 堆栈对象
     */
    private void printStacktrace(Throwable e) {
        Writer writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        e.printStackTrace(printWriter);
        Throwable cause = e.getCause();
        while (cause != null) {
            cause.printStackTrace(printWriter);
            cause = cause.getCause();
        }
        printWriter.close();
        String result = writer.toString();
        this.messager.printMessage(Diagnostic.Kind.ERROR, result);
    }

    /**
     * 生成定义临时变量的语句
     * @param varType 变量的类型
     * @param name 变量的变量名
     * @param init 变量的初始化语句
     * @return 生成临时变量的语句
     */
    private JCVariableDecl makeVarDef(String varType, String name, JCExpression init) {
        return treeMaker.VarDef(
                treeMaker.Modifiers(0), // 局部变量没有 Modifiers
                names.fromString(name), // 名字
                accessMember(varType), // 类型
                init // 初始化语句
        );
    }

    /**
     * 解析链式调用
     * @param selectExpr
     *      - 获取到某个类                com.sensorsdata.Test
     *      - 获取到某个类的某个静态方法     com.sensorsdata.Test.getUser
     *      - 获取到某个对象某个成员方法     test.getId
     * @return 解析该链式表达式的 Expression
     */
    private JCExpression accessMember(String selectExpr) {
        String[] selectExprArray = selectExpr.split("\\.");
        JCExpression expr = treeMaker.Ident(names.fromString(selectExprArray[0]));
        for (int i = 1; i < selectExprArray.length; i++) {
            String selector = selectExprArray[i];
            expr = treeMaker.Select(expr, names.fromString(selector));
        }
        return expr;
    }

    /**
     * 获取到 Type 对应的 class 对象，例如 int.class
     * @param type 要获取 class 对象的 Type
     * @return class 对象对应的 JCExpression
     */
    private JCExpression accessClass(Type type) {
        java.util.List<String> primitiveArray = new ArrayList<>();
        primitiveArray.add("int[]");
        primitiveArray.add("short[]");
        primitiveArray.add("long[]");
        primitiveArray.add("float[]");
        primitiveArray.add("double[]");
        primitiveArray.add("bool[]");
        primitiveArray.add("char[]");
        primitiveArray.add("byte[]");

        String components = type.toString();
        if (type.getTag() == TypeTag.ARRAY) {
            // 处理数组
            String componentsWithRidOfArray = components.substring(0, components.length() - 2);
            if (primitiveArray.contains(components)) {
                // 处理基本数据类型数组
                // 没有方法判断数组是否是基本数据类型的数组，因此只能对比 type.toString 来判断
                return treeMaker.Select(
                        treeMaker.TypeArray(treeMaker.TypeIdent(TypeTag.valueOf(componentsWithRidOfArray.toUpperCase()))),
                        names.fromString("class"));
            } else {
                // 处理引用数据类型的数组
                return treeMaker.Select(
                        treeMaker.TypeArray(accessMember(componentsWithRidOfArray)),
                        names.fromString("class")
                );
            }
        } else if (type.isPrimitive()) {
            // 处理基本数据类型
            return treeMaker.Select(treeMaker.TypeIdent(type.getTag()), names.fromString("class"));
        } else {
            // 处理引用数据类型
            return accessMember(components + ".class");
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_8;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotationSet = new HashSet<>();
        annotationSet.add(InitSensorsAnalytics.class.getCanonicalName());
        annotationSet.add(Track.class.getCanonicalName());
        annotationSet.add(Profile.class.getCanonicalName());
        annotationSet.add(Item.class.getCanonicalName());
        annotationSet.add(TrackSignUp.class.getCanonicalName());
        return annotationSet;
    }
}
