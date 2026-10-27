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
 *
 * Provides IDE features for engine resource key strings:
 * 1. Auto-completion: Suggests registered keys when typing in calls like getTexture("").
 * 2. Navigation: Ctrl + Click to jump to the key registration site.
 * 3. Error Highlighting: Displays in-place errors for unregistered or misspelled keys.
 * 4. Rename Refactoring: Shift + F6 renames both registration and all usage sites.
 */
public final class OlenEngineSupport {

    // Index ID for fast project-wide key lookups
    public static final ID<String, Void> INDEX_ID = ID.create("com.olen.engine.keys");
    // FQCN of the Fw class used for exact method qualifier matching
    private static final String FW_CLASS_FQCN = "com.fw.main.Fw";

    /**
     * Managed resource categories with presentation labels for completion items.
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
     * Utility and Category Classification Methods
     * ========================================================= */

    /**
     * Extracts the string literal expression under the cursor or target AST element.
     */
    public static PsiLiteralExpression extractLiteral(@Nullable PsiElement element) {
        if (element instanceof PsiLiteralExpression lit) return lit;
        if (element != null && element.getParent() instanceof PsiLiteralExpression lit) return lit;
        return null;
    }

    /**
     * Determines whether a literal expression represents a key declaration/definition.
     */
    public static KeyCategory getDefinitionCategory(@Nullable PsiExpression expr) {
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
     * Determines whether a literal expression represents a key lookup/usage.
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
                // Evaluates free(TYPE, "key") calls
                if (index == 1 && args.length >= 2) {
                    String typeArg = args[0].getText();
                    if (typeArg.contains("TEXTURE")) yield KeyCategory.TEXTURE;
                    if (typeArg.contains("MUSIC")) yield KeyCategory.MUSIC;
                    if (typeArg.contains("SOUND")) yield KeyCategory.SOUND;
                }
                yield null;
            }
            default -> null;
        };
    }

    /**
     * Checks if the AST position corresponds to a resource key usage site.
     */
    public static KeyCategory getInsideUsageCategory(PsiElement position) {
        if (position == null) return null;
        PsiLiteralExpression literal = extractLiteral(position);
        return getUsageCategory(literal);
    }

    /**
     * Verifies whether the method invocation originates from 'com.fw.main.Fw'.
     */
    private static boolean isFwQualifier(@NotNull PsiMethodCallExpression call) {
        PsiReferenceExpression methodExpr = call.getMethodExpression();
        PsiExpression qualifier = methodExpr.getQualifierExpression();
        Project project = call.getProject();
        boolean isDumb = DumbService.isDumb(project);

        // 1. Static import check (e.g., import static com.fw.main.Fw.get;)
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

        // 2. Fast textual qualifier matching (Fw.get(), com.fw.main.Fw.get())
        String qualifierText = qualifier.getText();
        if (!"Fw".equals(qualifierText) && !FW_CLASS_FQCN.equals(qualifierText) && !qualifierText.endsWith(".Fw")) {
            return false;
        }

        // 3. Skip type resolution during indexing mode
        if (isDumb) {
            return true;
        }

        // 4. Detailed FQCN verification in smart mode
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
     * Caches key definitions declared within the current file.
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
     * Project-wide background indexer storing keys as "CATEGORY#key_name".
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

        @Override
        public int getVersion() { return 11; }

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
     * Provides popup code completions for matching resource keys.
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
                    if (targetCat == null) return;

                    result.restartCompletionOnAnyPrefixChange();

                    // Strip IDE dummy completion markers and leading quotes
                    String rawPrefix = result.getPrefixMatcher().getPrefix();
                    String cleanPrefix = rawPrefix.startsWith("\"") ? rawPrefix.substring(1) : rawPrefix;
                    cleanPrefix = cleanPrefix.replace("IntellijIdeaRulezzz", "").trim();

                    CompletionResultSet customResult = result.withPrefixMatcher(new PlainPrefixMatcher(cleanPrefix));

                    Project project = position.getProject();
                    if (DumbService.isDumb(project)) return;

                    Set<String> words = new LinkedHashSet<>();

                    // 1. Collect keys defined in the currently edited file
                    PsiFile currentFile = parameters.getOriginalFile();
                    words.addAll(getFileDefinedKeysCache(currentFile).get(targetCat));

                    // 2. Query index for keys from other files matching the exact category prefix
                    String prefix = targetCat.name() + "#";
                    Collection<String> allKeys = FileBasedIndex.getInstance().getAllKeys(INDEX_ID, project);
                    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

                    for (String indexed : allKeys) {
                        if (indexed.startsWith(prefix)) {
                            Collection<VirtualFile> containingFiles =
                                    FileBasedIndex.getInstance().getContainingFiles(INDEX_ID, indexed, scope);
                            if (!containingFiles.isEmpty()) {
                                words.add(indexed.substring(prefix.length()));
                            }
                        }
                    }

                    if (words.isEmpty()) return;

                    // Prioritize candidates over standard plain-text tokens
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

                    result.stopHere();
                }
            });
        }
    }

    /* =========================================================
     * 3. CompletionConfidence
     * ========================================================= */

    /**
     * Forces immediate popup activation inside literal strings for resource keys.
     */
    public static class Confidence extends CompletionConfidence {
        public Confidence() {}

        @NotNull
        @Override
        public ThreeState shouldSkipAutopopup(@NotNull PsiElement contextElement, @NotNull PsiFile psiFile, int offset) {
            if (getInsideUsageCategory(contextElement) != null) {
                return ThreeState.NO;
            }
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
     * 4. PsiPolyVariantReference (Ctrl + Click Navigation)
     * ========================================================= */

    /**
     * Resolves literal expressions to their original declaration sites.
     */
    public static class KeyReference extends PsiReferenceBase<PsiLiteralExpression> implements PsiPolyVariantReference {
        private final String key;
        private final KeyCategory category;

        public KeyReference(@NotNull PsiLiteralExpression element, KeyCategory category) {
            super(element, ElementManipulators.getValueTextRange(element));
            this.key = element.getValue() instanceof String s ? s : "";
            this.category = category;
        }

        @NotNull
        @Override
        public ResolveResult[] multiResolve(boolean incompleteCode) {
            if (key.isBlank() || category == null) return ResolveResult.EMPTY_ARRAY;
            Project project = myElement.getProject();
            if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY;

            List<ResolveResult> results = new ArrayList<>();
            Set<PsiElement> visited = new HashSet<>();

            // 1. Search definitions in current file
            PsiFile currentFile = myElement.getContainingFile();
            for (PsiLiteralExpression lit : PsiTreeUtil.findChildrenOfType(currentFile, PsiLiteralExpression.class)) {
                if (key.equals(lit.getValue()) && getDefinitionCategory(lit) == category) {
                    if (visited.add(lit)) {
                        results.add(new PsiElementResolveResult(lit));
                    }
                }
            }

            // 2. Search definitions in indexed files
            String indexedKey = category.name() + "#" + key;
            Collection<VirtualFile> files = FileBasedIndex.getInstance()
                    .getContainingFiles(INDEX_ID, indexedKey, GlobalSearchScope.projectScope(project));

            for (VirtualFile vf : files) {
                if (vf.equals(currentFile.getVirtualFile())) continue;
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

        @Override
        public PsiElement handleElementRename(@NotNull String newElementName) {
            return ElementManipulators.handleContentChange(myElement, getRangeInElement(), newElementName);
        }
    }

    /**
     * Registers KeyReference onto literal expressions matching usage categories.
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
     * Highlights unregistered or misspelled keys in real-time.
     */
    public static class KeyAnnotator implements Annotator {
        public KeyAnnotator() {}

        @Override
        public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
            if (!(element instanceof PsiLiteralExpression lit)) return;

            KeyCategory targetCat = getUsageCategory(lit);
            if (targetCat == null) return;

            Project project = element.getProject();
            if (DumbService.isDumb(project)) return;

            Object val = lit.getValue();
            if (!(val instanceof String key) || key.isBlank()) return;

            // 1. Check local file definitions
            PsiFile currentFile = element.getContainingFile();
            if (getFileDefinedKeysCache(currentFile).get(targetCat).contains(key)) {
                return;
            }

            // 2. Check indexed project-wide definitions
            String indexedKey = targetCat.name() + "#" + key;
            Collection<VirtualFile> matches = FileBasedIndex.getInstance()
                    .getContainingFiles(INDEX_ID, indexedKey, GlobalSearchScope.projectScope(project));

            if (matches.isEmpty()) {
                holder.newAnnotation(HighlightSeverity.ERROR, "Unresolved " + targetCat.label + " key: \"" + key + "\"")
                        .range(lit)
                        .create();
            }
        }
    }

    /* =========================================================
     * 6. Rename Refactoring (OlenKeyRenameElement & Processor)
     * ========================================================= */

    /**
     * Virtual symbol element to bypass strict Java identifier validations for arbitrary key formats.
     */
    public static class OlenKeyRenameElement extends LightElement implements PsiNamedElement {
        private final PsiLiteralExpression definitionLiteral;
        private final KeyCategory category;
        private final String key;

        public OlenKeyRenameElement(@NotNull PsiLiteralExpression definitionLiteral,
                                    @NotNull KeyCategory category,
                                    @NotNull String key) {
            super(definitionLiteral.getManager(), PlainTextLanguage.INSTANCE);
            this.definitionLiteral = definitionLiteral;
            this.category = category;
            this.key = key;
        }

        @NotNull
        public PsiLiteralExpression getDefinitionLiteral() { return definitionLiteral; }

        @NotNull
        public KeyCategory getCategory() { return category; }

        @Override
        public String getName() { return key; }

        @Override
        public PsiElement setName(@NotNull String name) { return this; }

        @Override
        public String toString() { return "OlenKey [" + category + "]: " + key; }

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
     * Handles rename refactoring (Shift + F6) across all matching usages and declarations.
     */
    public static class KeyRenameProcessor extends RenamePsiElementProcessor {
        public KeyRenameProcessor() {}

        @Override
        public boolean canProcessElement(@NotNull PsiElement element) {
            if (element instanceof OlenKeyRenameElement) return true;
            PsiLiteralExpression lit = extractLiteral(element);
            return lit != null && (getDefinitionCategory(lit) != null || getUsageCategory(lit) != null);
        }

        @Nullable
        @Override
        public PsiElement substituteElementToRename(@NotNull PsiElement element, @Nullable Editor editor) {
            if (element instanceof OlenKeyRenameElement) return element;
            PsiLiteralExpression lit = extractLiteral(element);
            if (lit == null) return element;

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
                String k = lit.getValue() instanceof String s ? s : "";
                return new OlenKeyRenameElement(lit, usageCat, k);
            }

            KeyCategory defCat = getDefinitionCategory(lit);
            if (defCat != null) {
                String k = lit.getValue() instanceof String s ? s : "";
                return new OlenKeyRenameElement(lit, defCat, k);
            }

            return lit;
        }

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

            String searchWord = Arrays.stream(key.split("[^a-zA-Z0-9_]+"))
                    .filter(s -> !s.isBlank())
                    .findFirst()
                    .orElse(key);

            KeyCategory finalCat = cat;
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

            for (UsageInfo usage : usages) {
                PsiReference ref = usage.getReference();
                if (ref != null) {
                    ref.handleElementRename(newName);
                }
            }

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