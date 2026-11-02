/*
 * Teste JUnit para rodar DENTRO do repositorio oficial do Catroid
 * (catroid/src/test/java/org/catrobat/catroid/test/fnfport/).
 *
 * Desserializa o code.xml gerado por fnf_to_catrobat.py com o
 * XstreamSerializer real do Pocket Code e confere:
 *  - versao da linguagem, cenas, objetos, scripts e bricks;
 *  - que cada UserVariable/UserList usada por um brick e o MESMO objeto
 *    declarado no objeto (local) ou no programa (global) - ou seja, que as
 *    referencias XStream foram resolvidas como no aplicativo;
 *  - que looks/sons usados por bricks pertencem ao objeto;
 *  - que cada formula, convertida em tokens (como o editor de formulas faz) e
 *    reinterpretada pelo InternFormulaParser, gera a MESMA arvore;
 *  - que a rotina de migracao flattenAllLists e getRequiredResources rodam;
 *  - que o projeto pode ser serializado de novo (toXML) sem erro.
 *
 * Execucao: FNF_CATROBAT=/caminho/FNF_Port.catrobat ./gradlew :catroid:testCatroidDebugUnitTest \
 *              --tests org.catrobat.catroid.test.fnfport.FnfPortDeserializationTest
 */
package org.catrobat.catroid.test.fnfport;

import org.catrobat.catroid.ProjectManager;
import org.catrobat.catroid.common.LookData;
import org.catrobat.catroid.common.SoundInfo;
import org.catrobat.catroid.content.BroadcastScript;
import org.catrobat.catroid.content.Project;
import org.catrobat.catroid.content.Scope;
import org.catrobat.catroid.content.Scene;
import org.catrobat.catroid.content.Script;
import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.content.bricks.Brick;
import org.catrobat.catroid.content.bricks.FormulaBrick;
import org.catrobat.catroid.content.bricks.PlaySoundBrick;
import org.catrobat.catroid.content.bricks.UserListBrick;
import org.catrobat.catroid.content.bricks.UserVariableBrickInterface;
import org.catrobat.catroid.formulaeditor.Formula;
import org.catrobat.catroid.formulaeditor.FormulaElement;
import org.catrobat.catroid.formulaeditor.InternFormulaParser;
import org.catrobat.catroid.formulaeditor.UserList;
import org.catrobat.catroid.formulaeditor.UserVariable;
import org.catrobat.catroid.io.XstreamSerializer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FnfPortDeserializationTest {

	private static String readCodeXml(String path) throws Exception {
		try (ZipFile zip = new ZipFile(path)) {
			ZipEntry entry = zip.getEntry("code.xml");
			try (InputStream in = zip.getInputStream(entry)) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[1 << 16];
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
				}
				return out.toString("UTF-8");
			}
		}
	}

	private static Object field(Object o, String name) throws Exception {
		Field f = FormulaElement.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static String dump(FormulaElement e) throws Exception {
		if (e == null) {
			return "_";
		}
		if (e.getElementType() == FormulaElement.ElementType.BRACKET) {
			return dump((FormulaElement) field(e, "rightChild"));
		}
		StringBuilder sb = new StringBuilder();
		sb.append(e.getElementType()).append(':').append(e.getValue()).append('(');
		sb.append(dump((FormulaElement) field(e, "leftChild"))).append(',');
		sb.append(dump((FormulaElement) field(e, "rightChild")));
		for (FormulaElement c : e.additionalChildren) {
			sb.append(',').append(dump(c));
		}
		return sb.append(')').toString();
	}

	@Test
	public void loadGeneratedProject() throws Exception {
		String path = System.getenv("FNF_CATROBAT");
		if (path == null) {
			path = System.getProperty("fnf.catrobat");
		}
		assertTrue("defina FNF_CATROBAT", path != null && new File(path).exists());
		String xml = readCodeXml(path);

		Project project = (Project) XstreamSerializer.getInstance().getXstream().fromXML(xml);
		StringBuilder report = new StringBuilder();
		report.append("arquivo: ").append(path).append('\n');
		report.append("catrobatLanguageVersion: ").append(project.getCatrobatLanguageVersion()).append('\n');
		report.append("paisagem: ").append(project.getXmlHeader().islandscapeMode())
				.append(" tela: ").append(project.getXmlHeader().virtualScreenWidth).append('x')
				.append(project.getXmlHeader().virtualScreenHeight).append('\n');

		List<UserVariable> globals = project.getUserVariables();
		List<UserList> globalLists = project.getUserLists();
		Map<String, Integer> types = new TreeMap<>();
		int sprites = 0;
		int scripts = 0;
		int bricks = 0;
		int formulas = 0;
		int roundTripOk = 0;
		List<String> problems = new ArrayList<>();

		for (Scene scene : project.getSceneList()) {
			for (Sprite sprite : scene.getSpriteList()) {
				sprites++;
				for (Script script : sprite.getScriptList()) {
					scripts++;
					types.merge(script.getClass().getSimpleName(), 1, Integer::sum);
					if (script instanceof BroadcastScript
							&& ((BroadcastScript) script).getBroadcastMessage() == null) {
						problems.add(sprite.getName() + ": BroadcastScript sem mensagem");
					}
					List<Brick> flat = new ArrayList<>();
					script.addToFlatList(flat);
					for (Brick brick : flat) {
						bricks++;
						types.merge(brick.getClass().getSimpleName(), 1, Integer::sum);
						if (brick instanceof UserVariableBrickInterface) {
							UserVariable v = ((UserVariableBrickInterface) brick).getUserVariable();
							boolean found = false;
							for (UserVariable u : sprite.getUserVariables()) {
								found |= u == v;
							}
							for (UserVariable u : globals) {
								found |= u == v;
							}
							if (v == null || !found) {
								problems.add(sprite.getName() + ": " + brick.getClass().getSimpleName()
										+ " com variavel nao ligada ao escopo: " + (v == null ? "null" : v.getName()));
							}
						}
						if (brick instanceof UserListBrick) {
							UserList l = ((UserListBrick) brick).getUserList();
							boolean found = false;
							for (UserList u : sprite.getUserLists()) {
								found |= u == l;
							}
							for (UserList u : globalLists) {
								found |= u == l;
							}
							if (l == null || !found) {
								problems.add(sprite.getName() + ": lista nao ligada " + (l == null ? "null" : l.getName()));
							}
						}
						if (brick instanceof PlaySoundBrick) {
							SoundInfo s = ((PlaySoundBrick) brick).getSound();
							if (s == null || !sprite.getSoundList().contains(s)) {
								problems.add(sprite.getName() + ": som nao pertence ao objeto");
							}
						}
						if (brick instanceof FormulaBrick) {
							for (Formula f : ((FormulaBrick) brick).getFormulas()) {
								formulas++;
								FormulaElement root = f.getRoot();
								FormulaElement reparsed = new InternFormulaParser(root.getInternTokenList())
										.parseFormula(new Scope(project, sprite, null));
								String a = dump(root);
								String b = dump(reparsed);
								if (a.equals(b)) {
									roundTripOk++;
								} else if (problems.size() < 30) {
									problems.add(sprite.getName() + ": formula muda ao reabrir no editor:\n   "
											+ a + "\n   " + b);
								}
							}
						}
					}
				}
				for (LookData look : sprite.getLookList()) {
					if (look.getName() == null || look.getFile() == null && look.getName().isEmpty()) {
						problems.add(sprite.getName() + ": look invalido");
					}
				}
			}
		}
		ProjectManager.flattenAllLists(project);
		project.getRequiredResources();
		String again = XstreamSerializer.getInstance().getXstream().toXML(project);

		report.append("objetos: ").append(sprites).append(", scripts: ").append(scripts)
				.append(", bricks: ").append(bricks).append(", formulas: ").append(formulas)
				.append(", formulas identicas apos tokens->parser: ").append(roundTripOk).append('\n');
		report.append("variaveis globais: ").append(globals.size()).append(", listas globais: ")
				.append(globalLists.size()).append('\n');
		report.append("tipos: ").append(types).append('\n');
		report.append("reserializacao toXML: ").append(again.length()).append(" caracteres\n");
		report.append("problemas: ").append(problems.isEmpty() ? "nenhum" : String.join("\n", problems)).append('\n');
		System.out.println(report);
		String out = System.getenv("FNF_REPORT");
		if (out != null) {
			try (FileWriter w = new FileWriter(out)) {
				w.write(report.toString());
			}
		}
		assertEquals(1.12, project.getCatrobatLanguageVersion(), 1e-9);
		assertTrue(problems.toString(), problems.isEmpty());
	}
}
