package org.commcare.print;

import org.commcare.cases.entity.Entity;
import org.commcare.graph.model.GraphData;
import org.commcare.graph.util.GraphException;
import org.commcare.suite.model.DetailField;
import org.commcare.util.DetailFieldPrintInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.Serializable;

/**
 * Represents a single DetailField whose information may be used to populate a placeholder in a
 * print template for a Detail
 *
 * Created by amstone326 on 3/2/17.
 */
public class PrintableDetailField implements Serializable {

    private static final int GRAPH_WIDTH = 750;
    private static final int GRAPH_HEIGHT = 375;

    private String valueString;
    private String fieldForm;

    public PrintableDetailField(DetailFieldPrintInfo printInfo) {
        DetailField field = printInfo.field;
        Entity entity = printInfo.entity;
        int fieldIndex = printInfo.index;

        this.fieldForm = field.getTemplateForm();
        String fieldAsString = entity.getFieldString(fieldIndex);
        if ("".equals(fieldAsString) || fieldAsString == null) {
            // this field can't be automatically represented as a string
            if (isGraphDetailField()) {
                parseGraphPrintData(entity, field, fieldIndex);
            } else {
                // this field is of some other form for which printing is currently not supported
                this.valueString = "";
            }
        } else {
            this.valueString = fieldAsString;
        }
    }

    private void parseGraphPrintData(Entity entity, DetailField field, int fieldIndex) {
        try {
            Object evaluatedField = entity.getField(fieldIndex);
            String graphTitle = field.getHeader().evaluate();
            if ("".equals(graphTitle) || graphTitle == null) {
                // DO NOT CHANGE THIS -- Workaround to address the fact that a graph will not
                // render properly if the 'chart-title' <div> is empty
                graphTitle = " ";
            }
            String fullGraphHtml = ((GraphData)evaluatedField).getGraphHTML(graphTitle);
            this.valueString = fullGraphHtml;
        } catch (GraphException e) {
            this.valueString = String.format("<p>AN ERROR OCCURRED: %s</p>", e.getMessage());
        }
    }

    public String getFormattedValueString() {
        if (isGraphDetailField()) {
            return createIframeForGraphHtml(valueString);
        } else {
            // Replace any ascii new line characters generated by HQ with html break tag
            return valueString.replaceAll("\\x0A", "<br>");
        }
    }

    private static String createIframeForGraphHtml(String fullGraphHtml) {
        return "<iframe srcdoc=\""
                + scrubHtmlStringForUseAsAttribute(addStyleAttributes(fullGraphHtml))
                + "\" height=\"" + GRAPH_HEIGHT + "\" width=\"" + GRAPH_WIDTH + "\"></iframe>";
    }

    private static String scrubHtmlStringForUseAsAttribute(String htmlString) {
        return htmlString.replace("\"", "\'");
    }

    private static String addStyleAttributes(String htmlString) {
        Document graphDoc = Jsoup.parse(htmlString);
        Element htmlNode = graphDoc.getElementsByTag("html").get(0);
        htmlNode.attr("style", "height: 100%");
        Element bodyNode = graphDoc.getElementsByTag("body").get(0);
        bodyNode.attr("style", "height: 90%; margin:0;");
        return graphDoc.html();
    }

    public boolean isGraphDetailField() {
        return "graph".equals(fieldForm);
    }

    public boolean isPrintSupported() {
        return !"".equals(valueString);
    }

}
