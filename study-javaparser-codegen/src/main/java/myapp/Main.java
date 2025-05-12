package myapp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.BiPredicate;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 4)
            throw new IllegalArgumentException("arg count expect=4 actual=" + args.length + " args=" + args);
        if (!args[0].equals("--in")) {
            throw new IllegalArgumentException("args[0]: expect=\"--in\" actual=" + args[0]);
        }
        if (!args[2].equals("--out")) {
            throw new IllegalArgumentException("args[2]: expect=\"--out\" actual=" + args[2]);
        }
        
        ApiParamBeanGenerator apiParamBeanGenerator = new ApiParamBeanGenerator(Paths.get(args[3])); 
        Files.find(Paths.get(args[1]), Integer.MAX_VALUE, new InputFilePredicate()).forEach(apiParamBeanGenerator);
    }

    static class InputFilePredicate implements BiPredicate<Path, BasicFileAttributes> {
        @Override
        public boolean test(Path p, BasicFileAttributes a) {
            if (a.isRegularFile() && p.getFileName().toString().endsWith(".xlsx")) {
                return true;
            } else {
                return false;
            }
        }
    }
}
