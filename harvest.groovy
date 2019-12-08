@Grab(group='org.apache.jena', module='jena-core', version='2.10.1')

import org.sbml.libsbml.*
import com.hp.hpl.jena.rdf.model.*
import com.hp.hpl.jena.rdf.arp.*
import org.semanticweb.owlapi.io.* 
import org.semanticweb.owlapi.model.*
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary


def cli = new CliBuilder()
cli.with {
usage: 'Self'
  h longOpt:'help', 'this information'
  m longOpt:'model-directory', 'directory containing SBML model files', args:1, required:true
  o longOpt:'output-file', 'Output file containing the SBML files transformed into OWL', args:1, required:true
}
def opt = cli.parse(args)
if( !opt ) {
  //  cli.usage()
  return
}

def oboall = "obo-all/"
def modeldir = new File(opt.m)

/* necessary to load SBML Library */
A a = new A()

/* Initialize OWL stuff, create name->class mapping, etc */
def onturi = "http://bioonto.de/sbml.owl#"
def ontfile = new File("owl/model.owl")
def id2class = [:] // maps a name to an OWLClass
OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
OWLDataFactory factory = manager.getOWLDataFactory()


def genChebiKeggMapping (String oboall) {
  def chebi = new File(oboall+"chebi/chebi.obo")
  def map = [:]
  def current = ""
  chebi.eachLine {
    if (it.startsWith("id: ")) {
      current = it.substring(4)
    }
    if (it.startsWith("xref: KEGG COMPOUND:") && it.contains("\"KEGG COMPOUND\"")) {
      it = it.substring(it.indexOf(':')+1)
      it = it.substring(it.indexOf(':')+1)
      it = it.substring(0,it.indexOf(' '))
      map[it] = current
    }
    if (it.startsWith("xref: KEGG DRUG:") && it.contains("\"KEGG DRUG\"")) {
      it = it.substring(it.indexOf(':')+1)
      it = it.substring(it.indexOf(':')+1)
      it = it.substring(0,it.indexOf(' '))
      map[it] = current
    }
  }
  return map
}

def kegg2chebi = genChebiKeggMapping(oboall)



def loadOnts = { manager.loadOntologyFromOntologyDocument(new File(oboall+it+"/"+it+".owl")) }

def ontSet = new TreeSet()


def baseont = manager.loadOntologyFromOntologyDocument(ontfile)
ontSet.add(baseont)
ontSet.add(manager.loadOntologyFromOntologyDocument(new File(oboall+"go_xp_all/go_xp_all.obo")))
ontSet.add(loadOnts("cellular_component"))
ontSet.add(loadOnts("molecular_function"))
ontSet.add(loadOnts("cell"))
ontSet.add(loadOnts("chebi"))
ontSet.add(loadOnts("quality"))
ontSet.add(loadOnts("fma_lite"))

OWLOntology ontology = manager.createOntology(IRI.create(onturi), ontSet)
ontology.getClassesInSignature(true).each {
  def aa = it.toString()
  aa = aa.substring(aa.indexOf('#')+1,aa.length()-1)
  aa = aa.replaceAll("_",":")
  aa = aa.replaceAll("<http://purl.obolibrary.org/obo/","")
  if (id2class[aa] != null) {
  } else {
    id2class[aa] = it
  }
}
ontology.getObjectPropertiesInSignature(true).each {
  def aa = it.toString()
  aa = aa.substring(aa.indexOf('#')+1,aa.length()-1)
  aa = aa.replaceAll("_",":")
  if (id2class[aa] != null) {
  } else {
    id2class[aa] = it
  }
}

def ro2 = "http://bioonto.de/ro2.owl#"
def process = id2class["Process"]
def continuant = id2class["Continuant"]
def function = id2class["Function"]
def thing = factory.getOWLThing()

def getIDSet = { 
  def s = new TreeSet()
  def f = new File(oboall+it+"/"+it+".tbl")
  f.splitEachLine("\t", { s.add(it[0])
		    s } )
}
def pset = getIDSet("biological_process").collect{id2class[it]}
def fset = getIDSet("molecular_function").collect{id2class[it]}
def cset = getIDSet("cellular_component").collect{id2class[it]}

def initSuper = {
  def ax = factory.getOWLSubClassOfAxiom(id2class["GO:0008150"],process)
  manager.addAxiom(ontology, ax)
  ax = factory.getOWLSubClassOfAxiom(id2class["GO:0003674"],function)
  manager.addAxiom(ontology, ax)
  ax = factory.getOWLSubClassOfAxiom(id2class["GO:0005575"],continuant)
  manager.addAxiom(ontology, ax)
  ax = factory.getOWLSubClassOfAxiom(id2class["CHEBI:24431"],continuant)
  manager.addAxiom(ontology, ax)
  ax = factory.getOWLSubClassOfAxiom(id2class["CHEBI:36342"],continuant)
  manager.addAxiom(ontology, ax)
}
initSuper()

def addClass = {classname ->
  def cl = id2class[classname]
  if (cl == null) {
    cl = factory.getOWLClass(IRI.create(onturi+classname))
  }
  def ax = null
   if (pset.contains(classname)) {
     ax = factory.getOWLSubClassOfAxiom(cl,process)
   } else if (fset.contains(classname)) {
     ax = factory.getOWLSubClassOfAxiom(cl,function)
   } else if (cset.contains(classname)) {
     ax = factory.getOWLSubClassOfAxiom(cl,continuant)
   } else {
     ax = factory.getOWLSubClassOfAxiom(cl,factory.getOWLThing())
   }
  manager.addAxiom(ontology, ax)
  return cl
}

def addClass2 = {classname, sup ->
  def cl = id2class[classname]
  if (cl == null) {
    cl = factory.getOWLClass(IRI.create(onturi+classname))
    id2class[classname] = cl
  }
  ax = factory.getOWLSubClassOfAxiom(cl,sup)
  manager.addAxiom(ontology, ax)
  return cl
}

def miriamToName = { id ->
  id = id.substring(id.indexOf(':')+1)
  id = id.substring(id.indexOf(':')+1)
  def type = id.substring(0,id.indexOf(':'))
  id = id.substring(id.indexOf(':')+1)
  id = id.replaceAll("%3A",":")
  id = id.replaceAll("_",":")
  if (type=="kegg.compound") {
    addClass2(id,continuant)
  }
  if (type=="kegg.genes") {
    pset.add(addClass2(id,continuant))
  }
  if (type=="taxonomy") {
    addClass2(id,continuant)
  }
  if (type=="kegg.reaction") {
    pset.add(addClass2(id,process))
  }
  if (type=="kegg.pathway") {
    pset.add(addClass2(id,process))
  }
  if (type=="interpro") {
    id = "Interpro:"+id
    addClass2(id,id2class["CHEBI:36080"])
  }
  if (type=="uniprot") {
    id = "UniProt:"+id
    addClass2(id,id2class["CHEBI:36080"])
  }
  if (type=="ec-code") {
    id = "EC:"+id
    addClass2(id,id2class["CHEBI:36080"])
  }
  id
}

def makeAnnoListFromRDF = { rdf ->
  com.hp.hpl.jena.rdf.model.Model rdfModel = ModelFactory.createDefaultModel()
  RDFReader mr = new JenaReader()
  def m = rdfModel.read(new StringReader(rdf), "http://bioonto.de/sbml#")
  def ll = []
  def s = new HashSet()
  def pp = rdfModel.createProperty("http://biomodels.net/biology-qualifiers/","isVersionOf")
  m.listStatements(null,pp,null).each { s.add(it) }
  pp = rdfModel.createProperty("http://biomodels.net/biology-qualifiers/","is")
  m.listStatements(null,pp,null).each { s.add(it) }
  pp = rdfModel.createProperty("http://biomodels.net/biology-qualifiers/","hasVersion")
  m.listStatements(null,pp,null).each { s.add(it) }
  s.each {
    it.getResource().as(Bag).each{ll << it} 
  }
  ll = ll.collect { miriamToName(it.toString()) }
  ll = ll.collect {
    if (kegg2chebi[it]!=null) {
      kegg2chebi[it]
    } else {
      it
    }
  }
  ll = ll.collect { id2class[it] }


  pp = rdfModel.createProperty("http://biomodels.net/biology-qualifiers/","isPartOf")
  s = new HashSet()
  def ll2 = []
  m.listStatements(null,pp,null).each { 
    it.getResource().as(Bag).each{ll2 << it} 
  }
  ll2 = ll2.collect { miriamToName(it.toString()) }
  ll2 = ll2.collect {
    if (kegg2chebi[it]!=null) {
      kegg2chebi[it]
    } else {
      it
    }
  }
  ll2.each {
    if (id2class[it]!=null) {
      def ttt = factory.getOWLObjectSomeValuesFrom(id2class["part-of"],id2class[it])
      if (pset.contains(id2class[it])) {
	pset.add(ttt)
	ll << ttt
      } else if (cset.contains(id2class[it])) {
	cset.add(ttt)
	ll << ttt
      } else if (fset.contains(id2class[it])) {
	/* TODO: put disambiguation pattern for "has-part some Function" here */
	ll << ttt
      }
    } else {
      //      s.add(factory.getOWLObjectSomeValuesFrom(id2class["part-of"],factory.getOWLThing()))
    }
  }
  def intClass = factory.getOWLObjectIntersectionOf(s)
  //  ll << intClass


  pp = rdfModel.createProperty("http://biomodels.net/biology-qualifiers/","hasPart")
  s = new HashSet()
  ll2 = []
  m.listStatements(null,pp,null).each { 
    it.getResource().as(Bag).each{ll2 << it} 
  }
  ll2 = ll2.collect { it.toString() }
  ll2 = ll2.collect { it.substring(it.indexOf(':')+1) }
  ll2 = ll2.collect { it.substring(it.indexOf(':')+1) }
  ll2 = ll2.collect { it.substring(it.indexOf(':')+1) }
  ll2 = ll2.collect { it.replaceAll("%3A",":") }
  ll2 = ll2.collect { it.replaceAll("_",":") }
  ll2 = ll2.collect {
    if (kegg2chebi[it]!=null) {
      kegg2chebi[it]
    } else {
      it
    }
  }
  ll2.each {
    if (id2class[it]!=null) {
      def ttt = factory.getOWLObjectSomeValuesFrom(id2class["has-part"],id2class[it])
      if (pset.contains(id2class[it])) {
	pset.add(ttt)
	ll << ttt
      } else if (cset.contains(id2class[it])) {
	cset.add(ttt)
	ll << ttt
      } else if (fset.contains(id2class[it])) {
	/* TODO: put disambiguation pattern for "has-part some Function" here */
	ll << ttt
      }
    } else {
      //      s.add(factory.getOWLObjectSomeValuesFrom(id2class["has-part"],factory.getOWLThing()))
    }
  }
  intClass = factory.getOWLObjectIntersectionOf(s)
  //  ll << intClass

  ll.collect { it?it:factory.getOWLThing() }
}

/* prop is from RDFVocabulary, content usually a string literal */
def addAnno = {resource, prop, cont ->
  OWLAnnotation anno = factory.getOWLAnnotation(
    factory.getOWLAnnotationProperty(prop.getIRI()),
    factory.getOWLTypedLiteral(cont))
  def axiom = factory.getOWLAnnotationAssertionAxiom(resource.getIRI(),
						     anno)
  manager.addAxiom(ontology,axiom)
}

/**************************************************************/





def reader = new SBMLReader()

def modelList = []

modeldir.eachFile(groovy.io.FileType.FILES){ file ->
  def modelId = file.getName().substring(0,file.getName().indexOf("."))
  def document = reader.readSBML(file.toString())
  org.sbml.libsbml.Model model = document.getModel()
  if (model!=null) {
    def tree = model.getAnnotation()
    def exp = new Expando()
    modelList << exp
    exp.id = modelId
    exp.filename = file.toString()
    def mc = factory.getOWLClass(IRI.create(onturi+exp.id))
    def ax = factory.getOWLSubClassOfAxiom(mc,id2class["Model"])
    manager.addAxiom(ontology,ax)
    def mw = factory.getOWLClass(IRI.create(onturi+"world_of_"+exp.id))
    ax = factory.getOWLSubClassOfAxiom(mc,factory.getOWLObjectSomeValuesFrom(id2class["model-of"],mw))
    manager.addAxiom(ontology,ax)
    exp.c = mc
    exp.w = mw

    exp.modelId = model.getId()
    addAnno(mc,OWLRDFVocabulary.RDF_DESCRIPTION,exp.modelId)
    addAnno(mc,OWLRDFVocabulary.RDFS_LABEL,model.getName())

    if (tree.hasChild("RDF")) {
      def rdf = tree.getChild("RDF").toXMLString()
      def ll = makeAnnoListFromRDF(rdf)
      exp.anno = ll // exp.anno is the list of model annotations
      def modeltempset = new TreeSet()
      modeltempset.add(continuant)
      ll.each {
	if (fset.contains(it)) {
	  def tempc = it // addClass(it)
	  tempc = factory.getOWLObjectSomeValuesFrom(id2class["has-function"],tempc)
	  modeltempset.add(tempc)
	} else if (pset.contains(it)) {
	  def tempc = it // addClass(it)
	  tempc = factory.getOWLObjectSomeValuesFrom(id2class["has-function"],
						     factory.getOWLObjectAllValuesFrom(id2class["realized-by"],
										       tempc))
	  modeltempset.add(tempc)
	} else {
	  def tempc = it // addClass(it) // Continuant
	  tempc = factory.getOWLObjectSomeValuesFrom(id2class["has-part"],tempc)
	  modeltempset.add(tempc)
	}
      }
      def tcl = factory.getOWLObjectIntersectionOf(modeltempset)
      ax = factory.getOWLSubClassOfAxiom(exp.w,tcl)
      manager.addAxiom(ontology,ax)
    }
    
    exp.compartments = [:]
    def comp = model.getListOfCompartments()
    for (int i = 0 ; i < comp.size() ; i++) {
      def c = comp.get(i)
      def cc = factory.getOWLClass(IRI.create(onturi+c.getId()+"_in_"+exp.id))
      ax = factory.getOWLSubClassOfAxiom(cc,id2class["Compartment"])
      manager.addAxiom(ontology,ax)
      def cc2 = factory.getOWLClass(IRI.create(onturi+"world_of_"+c.getId()+"_in_"+exp.id))
      ax = factory.getOWLSubClassOfAxiom(cc2,id2class["Continuant"])
      manager.addAxiom(ontology,ax)

      addAnno(cc, OWLRDFVocabulary.RDF_DESCRIPTION, c.getName())

      def cexp = new Expando()
      cexp.id = c.getId()
      cexp.c = cc
      cexp.w = cc2

      ax = factory.getOWLSubClassOfAxiom(cexp.c,factory.getOWLObjectSomeValuesFrom(id2class["represents"],cexp.w))
      manager.addAxiom(ontology,ax)
      
      ax = factory.getOWLSubClassOfAxiom(cexp.c,factory.getOWLObjectSomeValuesFrom(id2class["part-of"],exp.c))
      manager.addAxiom(ontology,ax)
      ax = factory.getOWLSubClassOfAxiom(exp.c,factory.getOWLObjectSomeValuesFrom(id2class["has-part"],cexp.c))
      manager.addAxiom(ontology,ax)

      ax = factory.getOWLSubClassOfAxiom(cexp.w,factory.getOWLObjectSomeValuesFrom(id2class["part-of"],exp.w))
      manager.addAxiom(ontology,ax)
      ax = factory.getOWLSubClassOfAxiom(exp.w,factory.getOWLObjectSomeValuesFrom(id2class["has-part"],cexp.w))
      manager.addAxiom(ontology,ax)

      tree = c.getAnnotation()
      if (tree!=null) {
	if (tree.hasChild("RDF")) {
	  def rdf = tree.getChild("RDF").toXMLString()
	  def ll = makeAnnoListFromRDF(rdf)
	  //	  ll = ll.collect {addClass(it)}
	  cexp.anno = ll
	  ll.each {
	    ax = factory.getOWLSubClassOfAxiom(cexp.w,it)
	    manager.addAxiom(ontology,ax)
	  }
	}
	exp.compartments[cexp.id] = cexp
      } 
    }

    exp.species = [:]
    def species = model.getListOfSpecies()
    for (int i = 0 ; i < species.size() ; i++) {
      def c = species.get(i)
      def sexp = new Expando()
      sexp.compartment = ""
      if (c.isSetCompartment()) {
	sexp.compartment = model.getCompartment(c.getCompartment()).getId()
      }

      def cc = factory.getOWLClass(IRI.create(onturi+c.getId()+"_species_in_"+sexp.compartment+"_in_"+exp.id))
      ax = factory.getOWLSubClassOfAxiom(cc,id2class["Species"])
      manager.addAxiom(ontology,ax)
      def cc2 = factory.getOWLClass(IRI.create(onturi+"world_of_"+c.getId()+"_species_in_"+sexp.compartment+"_in_"+exp.id))
      ax = factory.getOWLSubClassOfAxiom(cc2,id2class["Continuant"])
      manager.addAxiom(ontology,ax)

      addAnno(cc, OWLRDFVocabulary.RDF_DESCRIPTION, c.getName())

      sexp.id = c.getId()
      sexp.c = cc
      sexp.w = cc2

      if (c.isSetInitialAmount()) { // the species has a mass quality
	ax = factory.getOWLSubClassOfAxiom(sexp.w,
					   factory.getOWLObjectSomeValuesFrom(id2class["has-quality"],id2class["PATO:0000125"]))
	manager.addAxiom(ontology,ax)
      }
      if (c.isSetCharge()) { // the species has a charge quality
	ax = factory.getOWLSubClassOfAxiom(sexp.w,
					   factory.getOWLObjectSomeValuesFrom(id2class["has-quality"],id2class["PATO:0002193"]))
	manager.addAxiom(ontology,ax)
      }
      if (c.isSetInitialConcentration()) { // the species has a concentration quality
	ax = factory.getOWLSubClassOfAxiom(sexp.w,
					   factory.getOWLObjectSomeValuesFrom(id2class["has-quality"],id2class["PATO:0000033"]))
	manager.addAxiom(ontology,ax)
      }

      ax = factory.getOWLSubClassOfAxiom(sexp.c,factory.getOWLObjectSomeValuesFrom(id2class["represents"],sexp.w))
      manager.addAxiom(ontology,ax)
      
      ax = factory.getOWLSubClassOfAxiom(sexp.c,factory.getOWLObjectSomeValuesFrom(id2class["part-of"],exp.c))
      manager.addAxiom(ontology,ax)
      ax = factory.getOWLSubClassOfAxiom(exp.c,factory.getOWLObjectSomeValuesFrom(id2class["has-part"],sexp.c))
      manager.addAxiom(ontology,ax)


      if (c.isSetCompartment()) {
	def tcomp = model.getCompartment(c.getCompartment())
	def compworld = factory.getOWLClass(IRI.create(onturi+"world_of_"+tcomp.getId()+"_in_"+exp.id))
	ax = factory.getOWLSubClassOfAxiom(sexp.w,factory.getOWLObjectSomeValuesFrom(id2class["contained-in"],compworld))
	manager.addAxiom(ontology,ax)
	ax = factory.getOWLSubClassOfAxiom(compworld,factory.getOWLObjectSomeValuesFrom(id2class["contains"],sexp.w))
	manager.addAxiom(ontology,ax)
      } else {
	ax = factory.getOWLSubClassOfAxiom(sexp.w,factory.getOWLObjectSomeValuesFrom(id2class["contained-in"],exp.w))
	manager.addAxiom(ontology,ax)
	ax = factory.getOWLSubClassOfAxiom(exp.w,factory.getOWLObjectSomeValuesFrom(id2class["contains"],sexp.w))
	manager.addAxiom(ontology,ax)
      }
      
      tree = c.getAnnotation()
      if (tree!=null) {
	if (tree.hasChild("RDF")) {
	  def rdf = tree.getChild("RDF").toXMLString()
	  def m = rdf=~ "\"[^ ]+miriam[^ ]+obo[^ ]+\""
	  def ll = makeAnnoListFromRDF(rdf)
	  //	  ll = ll.collect {addClass(it)}
	  sexp.anno = ll
	  ll.each {
	    ax = factory.getOWLSubClassOfAxiom(sexp.w,it)
	    manager.addAxiom(ontology,ax)
	  }
	}
	exp.species[sexp.id] = sexp
      }
    }

    def reactions = model.getListOfReactions()
    for (int i = 0 ; i < reactions.size() ; i++) {
      def c = reactions.get(i)
      def cc = factory.getOWLClass(IRI.create(onturi+c.getId()+"_in_"+exp.id))
      ax = factory.getOWLSubClassOfAxiom(cc,id2class["Reaction"])
      manager.addAxiom(ontology,ax)
      def cc2 = factory.getOWLClass(IRI.create(onturi+"world_of_"+c.getId()+"_in_"+exp.id))
      ax = factory.getOWLSubClassOfAxiom(cc2,id2class["Process"])
      manager.addAxiom(ontology,ax)

      addAnno(cc, OWLRDFVocabulary.RDF_DESCRIPTION, c.getName())

      def rexp = new Expando()
      rexp.id = c.getId()
      rexp.c = cc
      rexp.w = cc2

      def clcl = factory.getOWLObjectSomeValuesFrom(id2class["has-function"],
						    factory.getOWLObjectAllValuesFrom(id2class["realized-by"],rexp.w))
      ax = factory.getOWLSubClassOfAxiom(rexp.c,factory.getOWLObjectSomeValuesFrom(id2class["represents"],clcl))
      manager.addAxiom(ontology,ax)
      
      ax = factory.getOWLSubClassOfAxiom(rexp.c,factory.getOWLObjectSomeValuesFrom(id2class["part-of"],exp.c))
      manager.addAxiom(ontology,ax)
      ax = factory.getOWLSubClassOfAxiom(exp.c,factory.getOWLObjectSomeValuesFrom(id2class["has-part"],rexp.c))
      manager.addAxiom(ontology,ax)

      ax = factory.getOWLSubClassOfAxiom(rexp.w,factory.getOWLObjectSomeValuesFrom(id2class["occurs-in"],exp.w))
      manager.addAxiom(ontology,ax)
      /* The inverse does not hold, because not every world has such a process occuring; but something with this function...*/
      //      ax = factory.getOWLSubClassOfAxiom(exp.w,factory.getOWLObjectSomeValuesFrom(id2class["has-process-occuring"],rexp.w))
      //      manager.addAxiom(ontology,ax)
      ax = factory.getOWLSubClassOfAxiom(exp.w,factory.getOWLObjectSomeValuesFrom(id2class["has-part"],clcl))
      manager.addAxiom(ontology,ax)
      

      tree = c.getAnnotation()
      if (tree!=null) {
	if (tree.hasChild("RDF")) {
	  def rdf = tree.getChild("RDF").toXMLString()
	  def ll = makeAnnoListFromRDF(rdf)
	  rexp.anno = ll
	  ll.each {
	    if (pset.contains(it)) { // reaction is process
	      ax = factory.getOWLSubClassOfAxiom(rexp.w,it)
	    } else if (fset.contains(it)) {
	      ax = factory.getOWLSubClassOfAxiom(rexp.w,factory.getOWLObjectSomeValuesFrom(id2class["realizes"],it))
	    }
	    manager.addAxiom(ontology,ax)
	  }
	}
      } /* if (tree!=null) */
      def inputs = c.getListOfReactants()
      for (int j = 0 ; j < inputs.size() ; j++) {
	def input = model.getSpecies(inputs.get(j).getSpecies())
	def sexp = new Expando()
	sexp.compartment = ""
	if (input.isSetCompartment()) {
	  sexp.compartment = model.getCompartment(input.getCompartment()).getId()
	}

	def inputClass = factory.getOWLClass(IRI.create(onturi+"world_of_"+input.getId()+"_species_in_"+sexp.compartment+"_in_"+exp.id))
	ax = factory.getOWLSubClassOfAxiom(rexp.w,factory.getOWLObjectSomeValuesFrom(id2class["has-input"],inputClass))
	manager.addAxiom(ontology,ax)
      }

      def outputs = c.getListOfProducts()
      for (int j = 0 ; j < outputs.size() ; j++) {
	def output = model.getSpecies(outputs.get(j).getSpecies())
	def sexp = new Expando()
	sexp.compartment = ""
	if (output.isSetCompartment()) {
	  sexp.compartment = model.getCompartment(output.getCompartment()).getId()
	}

	def outputClass = factory.getOWLClass(IRI.create(onturi+"world_of_"+output.getId()+"_species_in_"+sexp.compartment+"_in_"+exp.id))
	ax = factory.getOWLSubClassOfAxiom(rexp.w,factory.getOWLObjectSomeValuesFrom(id2class["has-output"],outputClass))
	manager.addAxiom(ontology,ax)
      }

      def modifiers = c.getListOfModifiers()
      for (int j = 0 ; j < modifiers.size() ; j++) {
	def modifier = model.getSpecies(modifiers.get(j).getSpecies())
	def sexp = new Expando()
	sexp.compartment = ""
	if (modifier.isSetCompartment()) {
	  sexp.compartment = model.getCompartment(modifier.getCompartment()).getId()
	}

	def modifierClass = factory.getOWLClass(IRI.create(onturi+"world_of_"+modifier.getId()+"_species_in_"+sexp.compartment+"_in_"+exp.id))
	ax = factory.getOWLSubClassOfAxiom(rexp.w,factory.getOWLObjectSomeValuesFrom(id2class["has-modifier"],modifierClass))
	manager.addAxiom(ontology,ax)
      }

    }

    /*
    exp.parameter = [:]
    def params = model.getListOfParameters()
    for (int i = 0 ; i < params.size() ; i++) {
      def c = params.get(i)
      tree = c.getAnnotation()
      if (tree!=null) {
	def sexp = new Expando()
	if (tree.hasChild("RDF")) {
	  def rdf = tree.getChild("RDF").toXMLString()
	  def m = rdf=~ "\"[^ ]+miriam[^ ]+obo[^ ]+\""
	  def ll = [] // list of annotations for compartment
	  m.each {
	    it = it.substring(it.lastIndexOf(':')+1)
	    it = it.replaceAll("%3A",":")
	    it = it.replaceAll("\"","")
	    ll << it
	  }
	  sexp.anno = ll
	  sexp.id = c.getId()
	}
	exp.parameter[sexp.id] = sexp
      }
    }
    */
  }
}


def outfile = new File(opt.o)
def outfilename = outfile.getCanonicalPath()

manager.saveOntology(ontology, IRI.create("file:"+outfilename))

