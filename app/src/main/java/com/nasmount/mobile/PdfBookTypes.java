package com.nasmount.mobile;

import java.util.Locale;

final class PdfBookTypes {
    private PdfBookTypes(){}
    static boolean supports(String name){
        return name!=null && name.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }
}
