package myapp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier.Keyword;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

public class ApiParamBeanGenerator implements Consumer<Path> {
    final Path outputPath;

    public ApiParamBeanGenerator(Path outputPath) {
        this.outputPath = outputPath;
    }

    @Override
    public void accept(Path inputExcelFile) {
        try (Workbook workbook = WorkbookFactory.create(inputExcelFile.toFile(), null, true)) {
            generateParamBean(readSheet(workbook.getSheet("api")));
        } catch (EncryptedDocumentException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ApiEndpointInfo readSheet(Sheet sheet) {
        final int API_NAME_EN_ROW = 3 - 1;
        final int API_NAME_EN_COL = 4 - 1;
        final int REQ_BEGIN_ROW = 6 - 1;

        ApiEndpointInfo apiEndpointInfo = new ApiEndpointInfo(sheet.getRow(API_NAME_EN_ROW).getCell(API_NAME_EN_COL).getStringCellValue());


        for (int r = REQ_BEGIN_ROW; /* nop */ ; r++) {
            System.out.printf("%d\n", r);
            Row row = sheet.getRow(r);
            if (row == null || row.getCell(2) == null) {
                break;
            }
            System.out.printf("%d %s\n", r, row.getCell(2).toString());
            apiEndpointInfo.request.add(new ApiParamInfo( //
                    row.getCell(4).getStringCellValue(), // name
                    (int) row.getCell(2).getNumericCellValue(), // depth
                    row.getCell(5).getStringCellValue(), // type
                    0, // min
                    0 // max
            ));
        }
        return apiEndpointInfo;
    }

    void generateParamBean(ApiEndpointInfo apiEndpointInfo) throws IOException {

        CompilationUnit compilationUnit = new CompilationUnit();
        compilationUnit.setPackageDeclaration("myapp");
        ClassOrInterfaceDeclaration classOrInterfaceDeclaration0 = compilationUnit.addClass("AaaReq", Keyword.PUBLIC);

        compilationUnit.addImport("lombok.Data");
        //field.addAnnotation("Zzz");

        Deque<ApiParamJavaInfo> classDeclarationStack = new ArrayDeque<>();
        classDeclarationStack.push(new ApiParamJavaInfo(classOrInterfaceDeclaration0));

        for (ApiParamInfo pi : apiEndpointInfo.request) {
            try {
                while (pi.depth < classDeclarationStack.size()) {
                    ApiParamJavaInfo paramJavaInfo = classDeclarationStack.pop();
                    paramJavaInfo.innerClassDeclaration.forEach(x -> paramJavaInfo.classDeclaration.addMember(x));
                }
    
                if (classDeclarationStack.size() == pi.depth) {
                    if (pi.type.equals("object")) {
                        ClassOrInterfaceDeclaration classOrInterfaceDeclaration = new ClassOrInterfaceDeclaration(
                                new NodeList<>(), false, pi.name);
                        ApiParamJavaInfo paramJavaInfo = classDeclarationStack.peek();
                        paramJavaInfo.classDeclaration.addField(classOrInterfaceDeclaration.getNameAsString(), pi.name,
                                Keyword.PRIVATE);
                        classOrInterfaceDeclaration.addAnnotation("Data");
                        paramJavaInfo.innerClassDeclaration.add(classOrInterfaceDeclaration);
                        classDeclarationStack.push(new ApiParamJavaInfo(classOrInterfaceDeclaration));
                    } else {
                        classDeclarationStack.peek().classDeclaration.addField(getJavaTypeName(pi.type), pi.name,
                                Keyword.PRIVATE);
                    }
                }
            } catch (RuntimeException e) {
                System.err.printf("%s %s %s\n", pi.name, pi, classDeclarationStack.toString());
                throw e;
            }
        }

        while (0 < classDeclarationStack.size()) {
            ApiParamJavaInfo paramJavaInfo = classDeclarationStack.pop();
            paramJavaInfo.innerClassDeclaration.forEach(x -> paramJavaInfo.classDeclaration.addMember(x));
        }
        try {
            Path x = outputPath
                    .resolve(compilationUnit.getPackageDeclaration().get().getNameAsString().replace('.', '/'));
            Files.createDirectories(x);
            try (Writer w = Files.newBufferedWriter(x.resolve("AaaReq" + ".java"))) {
                w.write(compilationUnit.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(compilationUnit.toString());
    }

    String getJavaTypeName(String typeName) {
        switch (typeName) {
        case "string":
            return "String";
        default:
            throw new IllegalArgumentException(typeName);
        }
    }

    /*
    List<ApiParamInfo> getParamInfos() {
        return Arrays.asList(//
                new ApiParamInfo("kyotsuBu", 1, "object", 1, 1), //
                new ApiParamInfo("ifInfo", 2, "object", 1, 1), //
                new ApiParamInfo("ifSeq", 3, "string", 1, 1), //
                new ApiParamInfo("kobetsuBu", 1, "object", 1, 1), //
                new ApiParamInfo("keiyakushaInfo", 2, "object", 1, 1), //
                new ApiParamInfo("jyushoCode", 3, "string", 1, 1), //
                new ApiParamInfo("banchi1", 3, "string", 1, 1), //
                new ApiParamInfo("banchi2", 3, "string", 1, 1), //
                new ApiParamInfo("banchi3", 3, "string", 1, 1), //
                new ApiParamInfo("seikyuInfo", 2, "object", 1, 1), //
                new ApiParamInfo("jyushoCode", 3, "string", 1, 1), //
                new ApiParamInfo("banchi1", 3, "string", 1, 1), //
                new ApiParamInfo("banchi2", 3, "string", 1, 1), //
                new ApiParamInfo("banchi3", 3, "string", 1, 1) //
        );
    }
    */

    static class ApiParamJavaInfo {
        final ClassOrInterfaceDeclaration classDeclaration;
        final List<ClassOrInterfaceDeclaration> innerClassDeclaration = new ArrayList<>();

        ApiParamJavaInfo(ClassOrInterfaceDeclaration classDeclaration) {
            this.classDeclaration = classDeclaration;
        }
    }

    static class ApiEndpointInfo {
        final String name;
        final List<ApiParamInfo> request = new ArrayList<>();
        final List<ApiParamInfo> response = new ArrayList<>();
        ApiEndpointInfo(String name) {
            this.name = name;
        }
    }
    
    static class ApiParamInfo {
        final String name;
        final int depth;
        final String type;
        final int min;
        final int max;

        ApiParamInfo(String name, int depth, String type, int min, int max) {
            super();
            this.name = name;
            this.depth = depth;
            this.type = type;
            this.min = min;
            this.max = max;
        }
    }
}
