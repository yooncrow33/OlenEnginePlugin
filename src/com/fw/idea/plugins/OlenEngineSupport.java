package com.fw.idea.plugins;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.ProcessingContext;
import com.intellij.util.ThreeState;
import com.intellij.util.indexing.*;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * [OlenEngineSupport]
 * 엔진에서 사용하는 각종 리소스 키(문자열)를 일반 자바 변수처럼 다룰 수 있게 해주는 기능 모음.
 *
 * 1. 자동완성: getTexture("") 입력 시 등록된 텍스처 키 목록 추천
 * 2. 코드 이동: Ctrl + 클릭으로 키를 등록한 선언 위치로 점프
 * 3. 오류 검사: 등록되지 않은 오타 문자열에 실시간 빨간 줄 표시
 * 4. 일괄 변경: Shift + F6으로 키 이름을 바꾸면 등록부와 사용처가 한 번에 변경
 */
public final class OlenEngineSupport {

    // 전체 프로젝트에서 키 목록을 빠르게 찾기 위한 인덱스(색인) 고유 ID
    public static final ID<String, Void> INDEX_ID = ID.create("com.olen.engine.keys");
    // Fw 클래스의 풀 패키지 경로 (정확한 클래스 매칭 검증용)
    private static final String FW_CLASS_FQCN = "com.fw.main.Fw";

    /**
     * 관리 대상 리소스 분류. 자동완성 팝업 우측에 표시될 텍스트 라벨 포함.
     */
    public enum KeyCategory {
        TEXTURE("Olen Texture"),
        SOUND("Olen Sound"),
        MUSIC("Olen Music"),
        SPRITE("Olen Sprite"),
        FW("Olen Base"),
        EVENT("Olen Event");

        public final String label;
        KeyCategory(String label) { this.label = label; }
    }

    /* =========================================================
     * 공통 유틸 및 판별 로직
     * ========================================================= */

    /**
     * 커서가 위치한 요소나 상위 부모에서 큰따옴표 형태의 문자열 리터럴("...")을 추출합니다.
     */
    public static PsiLiteralExpression extractLiteral(@Nullable PsiElement element) {
        if (element instanceof PsiLiteralExpression lit) return lit;
        if (element != null && element.getParent() instanceof PsiLiteralExpression lit) return lit;
        return null;
    }

    /**
     * 특정 문자열이 '키를 새로 등록(정의)하는 위치'인지 파악합니다.
     * 메서드 이름과 파라미터 순서(인덱스)를 기준으로 리소스 종류를 구분합니다.
     * 예: registerBootTexture("bg") -> 첫 번째 파라미터(index 0)이므로 TEXTURE 키 정의로 판단.
     */
    public static KeyCategory getDefinitionCategory(@Nullable PsiExpression expr) {
        if (!(expr instanceof PsiLiteralExpression)) return null;
        if (!(expr.getParent() instanceof PsiExpressionList argList)) return null;
        if (!(argList.getParent() instanceof PsiMethodCallExpression call)) return null;

        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;

        // 현재 문자열이 해당 메서드의 몇 번째 인자값인지 확인
        PsiExpression[] args = argList.getExpressions();
        int index = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == expr) { index = i; break; }
        }
        if (index < 0) return null;

        return switch (methodName) {
            case "registerBootTexture" -> (index == 0) ? KeyCategory.TEXTURE : null;
            case "registerBootSound" -> (index == 0) ? KeyCategory.SOUND : null;
            case "registerBootMusic" -> (index == 0) ? KeyCategory.MUSIC : null;
            case "setStringKey" -> (index == 0) ? KeyCategory.FW : null;
            case "add" -> (index == 0 && isFwQualifier(call)) ? KeyCategory.FW : null;
            case "registerSprite" -> (index == 1) ? KeyCategory.SPRITE : null;
            case "loadTexture" -> {
                if (index == 1) yield KeyCategory.TEXTURE;
                if (index == 3) yield KeyCategory.EVENT;
                yield null;
            }
            case "loadSound" -> {
                if (index == 1) yield KeyCategory.SOUND;
                if (index == 3) yield KeyCategory.EVENT;
                yield null;
            }
            case "loadMusic" -> {
                if (index == 2) yield KeyCategory.MUSIC;
                if (index == 4) yield KeyCategory.EVENT;
                yield null;
            }
            default -> null;
        };
    }

    /**
     * 특정 문자열이 등록된 키를 '조회하거나 사용하는 위치'인지 판별합니다.
     * 예: getTexture("hero") -> 첫 번째 인자이므로 TEXTURE 사용처로 판별.
     */
    public static KeyCategory getUsageCategory(@Nullable PsiExpression expr) {
        if (!(expr instanceof PsiLiteralExpression)) return null;
        if (!(expr.getParent() instanceof PsiExpressionList argList)) return null;
        if (!(argList.getParent() instanceof PsiMethodCallExpression call)) return null;

        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;

        PsiExpression[] args = argList.getExpressions();
        int index = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == expr) { index = i; break; }
        }
        if (index < 0) return null;

        return switch (methodName) {
            case "getTexture" -> (index == 0) ? KeyCategory.TEXTURE : null;
            case "getSound" -> (index == 0) ? KeyCategory.SOUND : null;
            case "getMusic" -> (index == 0) ? KeyCategory.MUSIC : null;
            case "getUV" -> (index == 0) ? KeyCategory.SPRITE : null;
            case "event" -> (index == 0) ? KeyCategory.EVENT : null;
            case "get" -> (index == 0 && isFwQualifier(call)) ? KeyCategory.FW : null;
            case "free" -> {
                // free(TYPE, "key") 형태 대응
                if (index == 1 && args.length >= 2) {
                    String typeArg = args[0].getText();
                    if (typeArg.contains("TEXTURE")) yield KeyCategory.TEXTURE;
                    if (typeArg.contains("SOUND")) yield KeyCategory.SOUND;
                    if (typeArg.contains("MUSIC")) yield KeyCategory.MUSIC;
                }
                yield null;
            }
            default -> null;
        };
    }

    /**
     * 에디터 내 특정 위치(커서 또는 AST 노드)가 리소스 사용처에 해당하는지 확인합니다.
     */
    public static KeyCategory getInsideUsageCategory(PsiElement position) {
        if (position == null) return null;
        PsiLiteralExpression literal = extractLiteral(position);
        return getUsageCategory(literal);
    }

    /**
     * 호출된 메서드가 'com.fw.main.Fw' 클래스 소속인지 검증합니다.
     * get()이나 add()는 너무 흔한 메서드명이므로 오작동을 방지하기 위함입니다.
     */
    private static boolean isFwQualifier(@NotNull PsiMethodCallExpression call) {
        PsiReferenceExpression methodExpr = call.getMethodExpression();
        PsiExpression qualifier = methodExpr.getQualifierExpression();
        Project project = call.getProject();
        // 인텔리제이가 프로젝트 색인(Indexing) 중일 때는 타입 분석 API를 쓸 수 없어 'DumbMode'가 됩니다.
        boolean isDumb = DumbService.isDumb(project);

        // 1. static import 형태인 경우 (예: import static com.fw.main.Fw.get;)
        if (qualifier == null) {
            if (!isDumb) {
                PsiMethod method = call.resolveMethod();
                if (method != null) {
                    PsiClass containingClass = method.getContainingClass();
                    return containingClass != null && FW_CLASS_FQCN.equals(containingClass.getQualifiedName());
                }
            }
            return false;
        }

        // 2. 메서드 앞 객체 텍스트 빠른 필터링 (Fw.get(), com.fw.main.Fw.get() 형태)
        String qualifierText = qualifier.getText();
        if (!"Fw".equals(qualifierText) && !FW_CLASS_FQCN.equals(qualifierText) && !qualifierText.endsWith(".Fw")) {
            return false;
        }

        // 3. 인덱싱 중에는 타입 추적이 불가능하므로 텍스트 매칭만 확인하고 통과
        if (isDumb) {
            return true;
        }

        // 4. 인덱싱이 끝난 정상 모드에서는 실제 클래스 패키지 경로(FQCN)까지 정밀 검증
        PsiMethod method = call.resolveMethod();
        if (method != null) {
            PsiClass containingClass = method.getContainingClass();
            return containingClass != null && FW_CLASS_FQCN.equals(containingClass.getQualifiedName());
        }

        if (qualifier instanceof PsiReferenceExpression refExpr) {
            PsiElement target = refExpr.resolve();
            if (target instanceof PsiClass psiClass) {
                return FW_CLASS_FQCN.equals(psiClass.getQualifiedName());
            }
        }

        return false;
    }

    /**
     * 현재 열린 파일 내에 선언된 키 목록을 메모리에 캐싱합니다.
     * 타이핑할 때마다 파일 전체를 훑으면 타이핑 렉이 발생하므로 파일 내용이 바뀔 때만 재계산합니다.
     */
    public static Map<KeyCategory, Set<String>> getFileDefinedKeysCache(@NotNull PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> {
            Map<KeyCategory, Set<String>> map = new EnumMap<>(KeyCategory.class);
            for (KeyCategory cat : KeyCategory.values()) {
                map.put(cat, new HashSet<>());
            }
            if (file instanceof PsiJavaFile) {
                Collection<PsiLiteralExpression> literals = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression.class);
                for (PsiLiteralExpression lit : literals) {
                    KeyCategory cat = getDefinitionCategory(lit);
                    if (cat != null && lit.getValue() instanceof String s && !s.isBlank()) {
                        map.get(cat).add(s);
                    }
                }
            }
            return CachedValueProvider.Result.create(map, file);
        });
    }

    /* =========================================================
     * 1. FileBasedIndex
     * ========================================================= */
    /**
     * [백그라운드 키 인덱서]
     * 다른 파일에 등록된 수많은 키를 매번 모든 자바 파일을 열어 찾아볼 수 없기 때문에,
     * IDE가 백그라운드에서 프로젝트 내 모든 자바 파일을 읽어 "카테고리#키이름" 형태로 색인표를 만들어 둡니다.
     */
    public static class KeyIndex extends ScalarIndexExtension<String> {
        public KeyIndex() {}

        @NotNull
        @Override
        public ID<String, Void> getName() { return INDEX_ID; }

        @NotNull
        @Override
        public DataIndexer<String, Void, FileContent> getIndexer() {
            return inputData -> {
                PsiFile file = inputData.getPsiFile();
                if (!(file instanceof PsiJavaFile)) return Collections.emptyMap();

                Map<String, Void> map = new HashMap<>();
                // 파일 내의 모든 문자열을 뒤져서 리소스 등록 메서드에 들어있는 키만 뽑아냅니다.
                Collection<PsiLiteralExpression> literals = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression.class);
                for (PsiLiteralExpression lit : literals) {
                    KeyCategory cat = getDefinitionCategory(lit);
                    if (cat != null && lit.getValue() instanceof String s && !s.isBlank()) {
                        map.put(cat.name() + "#" + s, null);
                    }
                }
                return map;
            };
        }

        @NotNull
        @Override
        public KeyDescriptor<String> getKeyDescriptor() { return EnumeratorStringDescriptor.INSTANCE; }

        // 인덱싱 로직이 바뀌면 버전을 올려 인텔리제이가 기존 색인을 폐기하고 다시 긁도록 유도합니다.
        @Override
        public int getVersion() { return 10; }

        @NotNull
        @Override
        public FileBasedIndex.InputFilter getInputFilter() {
            return file -> file.getFileType() == JavaFileType.INSTANCE;
        }

        @Override
        public boolean dependsOnFileContent() { return true; }
    }

    /* =========================================================
     * 2. CompletionContributor
     * ========================================================= */
    /**
     * [코드 자동완성 추천]
     * getTexture("...") 따위의 함수 내부에서 타이핑할 때 사전에 등록된 키 목록을 팝업창으로 띄워줍니다.
     */
    public static class Contributor extends CompletionContributor {
        public Contributor() {
            extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<>() {
                @Override
                protected void addCompletions(@NotNull CompletionParameters parameters,
                                              @NotNull ProcessingContext context,
                                              @NotNull CompletionResultSet result) {

                    PsiElement position = parameters.getPosition();
                    KeyCategory targetCat = getInsideUsageCategory(position);
                    if (targetCat == null) return; // 리소스 사용 위치가 아니면 기본 자동완성 유지

                    result.restartCompletionOnAnyPrefixChange();

                    // 인텔리제이가 커서 위치를 추적할 때 임시로 붙이는 내부 마커("IntellijIdeaRulezzz") 및 따옴표 제거
                    String rawPrefix = result.getPrefixMatcher().getPrefix();
                    String cleanPrefix = rawPrefix.startsWith("\"") ? rawPrefix.substring(1) : rawPrefix;
                    cleanPrefix = cleanPrefix.replace("IntellijIdeaRulezzz", "").trim();

                    CompletionResultSet customResult = result.withPrefixMatcher(new PlainPrefixMatcher(cleanPrefix));

                    Project project = position.getProject();
                    if (DumbService.isDumb(project)) return;

                    Set<String> words = new LinkedHashSet<>();

                    // 1. 현재 편집 중인 파일에 등록된 최신 키 추가 (방금 선언한 키 즉각 반영)
                    PsiFile currentFile = parameters.getOriginalFile();
                    words.addAll(getFileDefinedKeysCache(currentFile).get(targetCat));

                    // 2. 다른 파일들에 등록된 키는 미리 만들어둔 색인(Index)에서 조회
                    String prefix = targetCat.name() + "#";
                    Collection<String> allKeys = FileBasedIndex.getInstance().getAllKeys(INDEX_ID, project);
                    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

                    for (String indexed : allKeys) {
                        if (indexed.startsWith(prefix)) {
                            // 실제 프로젝트 내에 존재하는 파일인지 검증
                            Collection<VirtualFile> containingFiles =
                                    FileBasedIndex.getInstance().getContainingFiles(INDEX_ID, indexed, scope);
                            if (!containingFiles.isEmpty()) {
                                words.add(indexed.substring(prefix.length()));
                            }
                        }
                    }

                    if (words.isEmpty()) return;

                    // 완성 후보 등록 (우선순위를 높여서 일반 단어 추천보다 최상단에 노출)
                    for (String word : words) {
                        customResult.addElement(
                                PrioritizedLookupElement.withPriority(
                                        LookupElementBuilder.create(word)
                                                .withTypeText(targetCat.label)
                                                .withBoldness(true),
                                        100000.0
                                )
                        );
                    }

                    // 우리 키 자동완성이 처리되었으면 불필요한 기본 단어 자동완성은 중단
                    result.stopHere();
                }
            });
        }
    }

    /* =========================================================
     * 3. CompletionConfidence
     * ========================================================= */
    /**
     * [자동완성 팝업 즉시 호출 정책]
     * 원래 인텔리제이는 문자열 따옴표("") 안에서는 단어 자동완성을 자동으로 잘 띄우지 않습니다.
     * 이 클래스는 리소스 키를 입력하는 위치라면 따옴표 안이라도 즉시 팝업을 띄우도록 만듭니다.
     */
    public static class Confidence extends CompletionConfidence {
        public Confidence() {}

        @NotNull
        @Override
        public ThreeState shouldSkipAutopopup(@NotNull PsiElement contextElement, @NotNull PsiFile psiFile, int offset) {
            // 현재 커서가 리소스 사용 위치라면 자동완성 생략을 거부(NO)하고 즉시 팝업 활성화
            if (getInsideUsageCategory(contextElement) != null) {
                return ThreeState.NO;
            }
            // 커서 바로 앞 글자 위치 기준 확인
            if (offset > 0) {
                PsiElement prev = psiFile.findElementAt(offset - 1);
                if (getInsideUsageCategory(prev) != null) {
                    return ThreeState.NO;
                }
            }
            return ThreeState.UNSURE;
        }
    }

    /* =========================================================
     * 4. PsiPolyVariantReference (코드 추적 및 Ctrl+클릭 이동)
     * ========================================================= */
    /**
     * [문자열과 선언부의 연결 고리]
     * 단순 문자열이지만, 인텔리제이에게 "이 문자열은 사실 저기서 선언된 키를 참조하고 있는 코드"라고 알려주는 클래스입니다.
     * 이를 통해 Ctrl + 클릭 이동 및 단어 변경 시 자동 추적이 가능해집니다.
     */
    public static class KeyReference extends PsiReferenceBase<PsiLiteralExpression> implements PsiPolyVariantReference {
        private final String key;
        private final KeyCategory category;

        public KeyReference(@NotNull PsiLiteralExpression element, KeyCategory category) {
            // 큰따옴표 안쪽의 알맹이 텍스트 범위만 참조 영역으로 설정
            super(element, ElementManipulators.getValueTextRange(element));
            this.key = element.getValue() instanceof String s ? s : "";
            this.category = category;
        }

        /**
         * [선언부 탐색]
         * Ctrl + 클릭을 눌렀을 때 이 키가 어디에서 등록되었는지 모든 후보를 찾아냅니다.
         */
        @NotNull
        @Override
        public ResolveResult[] multiResolve(boolean incompleteCode) {
            if (key.isBlank() || category == null) return ResolveResult.EMPTY_ARRAY;
            Project project = myElement.getProject();
            if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY;

            List<ResolveResult> results = new ArrayList<>();
            Set<PsiElement> visited = new HashSet<>();

            // 1. 현재 파일 내에 선언부가 있는지 먼저 탐색
            PsiFile currentFile = myElement.getContainingFile();
            for (PsiLiteralExpression lit : PsiTreeUtil.findChildrenOfType(currentFile, PsiLiteralExpression.class)) {
                if (key.equals(lit.getValue()) && getDefinitionCategory(lit) == category) {
                    if (visited.add(lit)) {
                        results.add(new PsiElementResolveResult(lit));
                    }
                }
            }

            // 2. 색인(Index)을 통해 다른 파일에 선언부가 있는지 탐색
            String indexedKey = category.name() + "#" + key;
            Collection<VirtualFile> files = FileBasedIndex.getInstance()
                    .getContainingFiles(INDEX_ID, indexedKey, GlobalSearchScope.projectScope(project));

            for (VirtualFile vf : files) {
                if (vf.equals(currentFile.getVirtualFile())) continue; // 이미 확인한 현재 파일은 스킵
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (!(psiFile instanceof PsiJavaFile)) continue;

                for (PsiLiteralExpression lit : PsiTreeUtil.findChildrenOfType(psiFile, PsiLiteralExpression.class)) {
                    if (key.equals(lit.getValue()) && getDefinitionCategory(lit) == category) {
                        if (visited.add(lit)) {
                            results.add(new PsiElementResolveResult(lit));
                        }
                    }
                }
            }

            return results.toArray(new ResolveResult[0]);
        }

        @Nullable
        @Override
        public PsiElement resolve() {
            ResolveResult[] results = multiResolve(false);
            return results.length >= 1 ? results[0].getElement() : null;
        }

        @NotNull
        @Override
        public Object[] getVariants() {
            return new Object[0];
        }

        /**
         * 사용처의 키와 대상 요소가 동일한 리소스를 가리키는지 확인합니다.
         */
        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            if (element instanceof OlenKeyRenameElement keyElem) {
                return key.equals(keyElem.getName()) && category == keyElem.getCategory();
            }
            if (element instanceof PsiLiteralExpression lit) {
                return key.equals(lit.getValue()) && category == getDefinitionCategory(lit);
            }
            PsiElement resolved = resolve();
            return resolved != null && resolved.equals(element);
        }

        /**
         * 리팩토링으로 키 이름이 바뀔 때 문자열 내용물을 교체합니다.
         */
        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) {
            return ElementManipulators.handleContentChange(myElement, getRangeInElement(), newElementName);
        }
    }

    /**
     * 문자열 요소가 사용처 함수 안에 들어있으면 위에서 정의한 KeyReference를 부착해 주는 등록기.
     */
    public static class ReferenceContributor extends PsiReferenceContributor {
        public ReferenceContributor() {}

        @Override
        public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
            registrar.registerReferenceProvider(
                    PlatformPatterns.psiElement(PsiLiteralExpression.class),
                    new PsiReferenceProvider() {
                        @Override
                        public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
                            if (element instanceof PsiLiteralExpression lit) {
                                KeyCategory cat = getUsageCategory(lit);
                                if (cat != null) {
                                    return new PsiReference[]{new KeyReference(lit, cat)};
                                }
                            }
                            return PsiReference.EMPTY_ARRAY;
                        }
                    }
            );
        }
    }

    /* =========================================================
     * 5. KeyAnnotator
     * ========================================================= */
    /**
     * [실시간 문법 오류 검사기]
     * getTexture("typo_key") 처럼 등록되지 않은 키를 쓰면 컴파일 전에 빨간 줄(에러)을 그어줍니다.
     */
    public static class KeyAnnotator implements Annotator {
        public KeyAnnotator() {}

        @Override
        public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
            if (!(element instanceof PsiLiteralExpression lit)) return;

            KeyCategory targetCat = getUsageCategory(lit);
            if (targetCat == null) return; // 리소스 사용처가 아니면 검사 패스

            Project project = element.getProject();
            if (DumbService.isDumb(project)) return;

            Object val = lit.getValue();
            if (!(val instanceof String key) || key.isBlank()) return;

            // 1. 현재 파일에 정의된 키인지 확인
            PsiFile currentFile = element.getContainingFile();
            if (getFileDefinedKeysCache(currentFile).get(targetCat).contains(key)) {
                return; // 등록된 키이므로 정상
            }

            // 2. 다른 파일에 정의된 키인지 색인(Index)에서 확인
            String indexedKey = targetCat.name() + "#" + key;
            Collection<VirtualFile> matches = FileBasedIndex.getInstance()
                    .getContainingFiles(INDEX_ID, indexedKey, GlobalSearchScope.projectScope(project));

            // 프로젝트 전체 어디에도 등록된 적 없는 키라면 에러 표시
            if (matches.isEmpty()) {
                holder.newAnnotation(HighlightSeverity.ERROR, "존재하지 않는 " + targetCat.label + " 키입니다: \"" + key + "\"")
                        .range(lit)
                        .create();
            }
        }
    }

    /* =========================================================
     * 6. RenamePsiElementProcessor & Virtual Rename Element
     * ========================================================= */

    /**
     * [가상 심볼 객체 (LightElement)]
     * 인텔리제이는 기본적으로 자바 식별자 규칙(특수문자 불가, 숫자로 시작 불가 등)을 엄격하게 검증합니다.
     * 하지만 리소스 키는 파일 경로(/ui/btn.png)나 특수문자가 들어갈 수 있으므로,
     * 자바 변수 검증을 우회하고 순수 텍스트 리팩토링 창을 띄우기 위해 만들어낸 '가상 요소'입니다.
     */
    public static class OlenKeyRenameElement extends LightElement implements PsiNamedElement {
        private final PsiLiteralExpression definitionLiteral;
        private final KeyCategory category;
        private final String key;

        public OlenKeyRenameElement(@NotNull PsiLiteralExpression definitionLiteral,
                                    @NotNull KeyCategory category,
                                    @NotNull String key) {
            // 자바 언어가 아닌 PlainText 언어로 속여서 자바 변수 이름 규칙 검증을 통과합니다.
            super(definitionLiteral.getManager(), PlainTextLanguage.INSTANCE);
            this.definitionLiteral = definitionLiteral;
            this.category = category;
            this.key = key;
        }

        @NotNull
        public PsiLiteralExpression getDefinitionLiteral() {
            return definitionLiteral;
        }

        @NotNull
        public KeyCategory getCategory() {
            return category;
        }

        @Override
        public String getName() {
            return key;
        }

        @Override
        public PsiElement setName(@NotNull String name) {
            return this;
        }

        @Override
        public String toString() {
            return "OlenKey [" + category + "]: " + key;
        }

        @Override
        public PsiFile getContainingFile() {
            return definitionLiteral.isValid() ? definitionLiteral.getContainingFile() : null;
        }

        @Override
        public TextRange getTextRange() {
            return definitionLiteral.isValid() ? definitionLiteral.getTextRange() : TextRange.EMPTY_RANGE;
        }

        @Override
        public boolean isEquivalentTo(PsiElement another) {
            if (another == this || another == definitionLiteral) return true;
            if (another instanceof OlenKeyRenameElement other) {
                return Objects.equals(key, other.key) && category == other.category;
            }
            return false;
        }
    }

    /**
     * [Shift + F6 일괄 이름 변경 처리기]
     * 등록부나 사용처 문자열에서 Shift + F6을 눌렀을 때, 관련된 모든 사용처와 선언부를 한꺼번에 바꿉니다.
     */
    public static class KeyRenameProcessor extends RenamePsiElementProcessor {
        public KeyRenameProcessor() {}

        @Override
        public boolean canProcessElement(@NotNull PsiElement element) {
            if (element instanceof OlenKeyRenameElement) return true;
            PsiLiteralExpression lit = extractLiteral(element);
            return lit != null && (getDefinitionCategory(lit) != null || getUsageCategory(lit) != null);
        }

        /**
         * 사용자가 리네임을 실행했을 때, 대상을 위에서 만든 가상 요소(OlenKeyRenameElement)로 바꿔치기합니다.
         */
        @Nullable
        @Override
        public PsiElement substituteElementToRename(@NotNull PsiElement element, @Nullable Editor editor) {
            if (element instanceof OlenKeyRenameElement) return element;
            PsiLiteralExpression lit = extractLiteral(element);
            if (lit == null) return element;

            // 1. 사용처 문자열 위에서 Rename을 누른 경우 -> 연결된 선언부를 찾아와서 타겟으로 지정
            KeyCategory usageCat = getUsageCategory(lit);
            if (usageCat != null) {
                for (PsiReference ref : lit.getReferences()) {
                    if (ref instanceof KeyReference) {
                        PsiElement resolved = ref.resolve();
                        if (resolved instanceof PsiLiteralExpression defLit) {
                            KeyCategory defCat = getDefinitionCategory(defLit);
                            String k = defLit.getValue() instanceof String s ? s : "";
                            return new OlenKeyRenameElement(defLit, defCat != null ? defCat : usageCat, k);
                        }
                    }
                }
                // 아직 등록부가 없는 키라도 해당 사용처 이름을 바꿀 수 있도록 지원
                String k = lit.getValue() instanceof String s ? s : "";
                return new OlenKeyRenameElement(lit, usageCat, k);
            }

            // 2. 등록부 선언 문자열 위에서 Rename을 누른 경우
            KeyCategory defCat = getDefinitionCategory(lit);
            if (defCat != null) {
                String k = lit.getValue() instanceof String s ? s : "";
                return new OlenKeyRenameElement(lit, defCat, k);
            }

            return lit;
        }

        /**
         * 이름이 바뀔 때 함께 수정되어야 하는 모든 사용처를 프로젝트 전체에서 수집합니다.
         */
        @NotNull
        @Override
        public Collection<PsiReference> findReferences(@NotNull PsiElement element,
                                                       @NotNull SearchScope searchScope,
                                                       boolean searchInCommentsAndStrings) {
            KeyCategory cat;
            String key;

            if (element instanceof OlenKeyRenameElement keyElem) {
                cat = keyElem.getCategory();
                key = keyElem.getName();
            } else {
                PsiLiteralExpression lit = extractLiteral(element);
                if (lit == null) return Collections.emptyList();
                cat = getDefinitionCategory(lit);
                if (cat == null) cat = getUsageCategory(lit);
                if (cat == null) return Collections.emptyList();
                key = lit.getValue() instanceof String s ? s : null;
            }

            if (key == null || key.isBlank()) return Collections.emptyList();

            Set<PsiReference> references = new LinkedHashSet<>();
            Project project = element.getProject();
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // 특수문자가 포함된 경로(예: "/ui/btn.png")는 단어 검색 엔진에서 조각나므로, 첫 유효 단어(ui)로 1차 필터링
            String searchWord = Arrays.stream(key.split("[^a-zA-Z0-9_]+"))
                    .filter(s -> !s.isBlank())
                    .findFirst()
                    .orElse(key);

            KeyCategory finalCat = cat;
            // 텍스트 검색 후 실제 동일한 카테고리의 리소스 키 참조인지 확인하여 리스트에 등록
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                    (foundElement, offsetInElement) -> {
                        PsiLiteralExpression usageLit = PsiTreeUtil.getParentOfType(foundElement, PsiLiteralExpression.class, false);
                        if (usageLit != null && key.equals(usageLit.getValue()) && getUsageCategory(usageLit) == finalCat) {
                            for (PsiReference ref : usageLit.getReferences()) {
                                if (ref instanceof KeyReference) {
                                    references.add(ref);
                                }
                            }
                        }
                        return true;
                    },
                    scope,
                    searchWord,
                    UsageSearchContext.IN_STRINGS,
                    true
            );
            return references;
        }

        /**
         * 사용자가 새 이름을 입력하고 확인을 눌렀을 때, 실제로 파일들을 순회하며 문자열을 새 이름으로 교체합니다.
         */
        @Override
        public void renameElement(@NotNull PsiElement element,
                                  @NotNull String newName,
                                  @NotNull UsageInfo[] usages,
                                  @Nullable RefactoringElementListener listener) {
            PsiLiteralExpression targetLit;
            if (element instanceof OlenKeyRenameElement keyElem) {
                targetLit = keyElem.getDefinitionLiteral();
            } else {
                targetLit = extractLiteral(element);
            }

            // 1. 모든 사용처(getTexture 등)의 문자열 텍스트 일괄 수정
            for (UsageInfo usage : usages) {
                PsiReference ref = usage.getReference();
                if (ref != null) {
                    ref.handleElementRename(newName);
                }
            }

            // 2. 키를 등록했던 원본 선언부(registerBootTexture 등) 문자열 수정
            PsiElement newElement = null;
            if (targetLit != null && targetLit.isValid() && getDefinitionCategory(targetLit) != null) {
                newElement = ElementManipulators.handleContentChange(
                        targetLit,
                        ElementManipulators.getValueTextRange(targetLit),
                        newName
                );
            }

            if (listener != null) {
                listener.elementRenamed(newElement != null ? newElement : element);
            }
        }
    }
}